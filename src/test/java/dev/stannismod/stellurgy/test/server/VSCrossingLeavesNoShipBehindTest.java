package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.ShipReadiness;

import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A per-ship crossing MOVES a ship, so it must not ADD one to the world it left.
 *
 * <p>A crossing snapshots the source ship's subspace shipyard, cuts those blocks, pastes them elsewhere
 * and re-assembles a fresh ship there. One ship goes away exactly as one appears, so the number of ships
 * the source world holds is CONSERVED across it. Both legs assert that single invariant.</p>
 *
 * <p><b>Two counters, each blind to one of the two ways a ship can be left behind.</b>
 * {@code vs ship-count} is the LOADED physics-object set; {@code vs ship-count-all} is the queryable
 * REGISTRY. A ship object left loaded but unregistered is invisible to the second; a registry entry left
 * behind with nothing loaded to collect it is invisible to the first. Both are read on every crossing, so
 * neither leak can hide behind the counter that cannot see it. The unregistered-object half is what this
 * class is aimed at; the other half has its own arrangement in
 * the unloaded-source scenarios at the end of this class, because it needs the opposite starting
 * state.</p>
 *
 * <p><b>Measured as a delta, never against zero.</b> The server is shared across this class's methods, so
 * each crossing is measured against counts taken immediately before it.</p>
 *
 * <p><b>The enabling condition is asserted, not assumed.</b> A ship OBJECT can only be stranded if the
 * source was loaded when its blocks were cut, so each leg asserts the source is loaded before it crosses
 * — otherwise a leg whose arrangement quietly drifted would pass while measuring nothing.</p>
 *
 * <p><b>Why arrival is checked by POSE.</b> The ship lookup behind {@code vs ship-info} returns the
 * nearest loaded ship at any distance, so on a build that strands ships it answers with the stranded one
 * and "a ship is managed here" would be true before anything arrived. Every arrival check therefore
 * requires the resolved ship to actually be AT the destination.</p>
 *
 * <p><b>Why the loaded-source legs never pump {@code vs load-ships}.</b> It is not needed — a ship is
 * created already loaded, and {@code permaload} keeps it that way, so the loaded set fills itself.
 * Only the unloaded-source scenario pumps, with {@code permaload} off for itself, because its arrival
 * is dropped by the load controller before it can be read. (A pump while {@code permaload} holds used
 * to kill the server when a registered ship was unloaded; that is pinned in
 * {@code SpaceSlotVsShipPersistTest}.)</p>
 *
 * <p><b>And the announcements that go with those transitions.</b> The scenarios at the end count what
 * production ANNOUNCES as a craft is built, dropped, fetched back and crossed — one
 * {@code ship_lifecycle} record per transition, saying which one it was. A count of ships is a
 * statement about the world; an announcement is what every consumer that acts at those moments
 * (the mass recompute, a durable record minted at birth) is told, and the two must agree about
 * what happened.</p>
 */
public class VSCrossingLeavesNoShipBehindTest extends AbstractSharedServerTest {


    private static final int BASE_Z = 5400;
    /** Where a ship is built, and the clear-sky altitude every crossing lands at. */
    private static final int BUILD_Y = FixtureSite.OPEN_AIR_Y, SKY_Y = 150;
    /** One base per method, far enough apart that no method can resolve another's ship. */
    private static final int LEG1_X = 5400, LEG2_X = 6000;
    /** Distance between a crossing's source and its destination — well beyond {@link #POSE_TOLERANCE}. */
    private static final int HOP = 160;
    /** How far a re-assembled ship's own pose may sit from the anchor it was seeded on. */
    private static final double POSE_TOLERANCE = 64.0;

    /**
     * How much WORLD a bounded wait is allowed: 200 server ticks, the ten seconds the old
     * {@code 40 x 250 ms} meant on an idle box. On the SERVER's clock, because what these wait for —
     * a queued assembly being drained, a queued load being served — is driven by the server tick
     * loop itself, and the worlds involved are often the ones that have not started ticking yet.
     */
    private static final int WAIT_TICKS = 200;

    /** The defect in one crossing: the ship object left behind in the world the crossing departed. */
    @Test
    public void aCrossingDoesNotLeaveAShipInTheWorldItLeft() throws Exception {

        // This leg measures the ship OBJECT a crossing strands, which can only exist if the source
        // is loaded when it is cut — which `buildShipAt` now establishes on the substrate's own
        // records rather than on a positional poll.
        buildShipAt(LEG1_X);

        crossConserving(LEG1_X, BUILD_Y, LEG1_X + HOP, SKY_Y, "the crossing");
    }

    /** The same leak three crossings deep — the shape a player walks (entry, jump, descent). */
    @Test
    public void threeCrossingsDoNotAccumulateShips() throws Exception {

        buildShipAt(LEG2_X);

        int x = LEG2_X, y = BUILD_Y;
        for (int i = 1; i <= 3; i++) {
            crossConserving(x, y, x + HOP, SKY_Y, "crossing " + i + " of 3");
            x += HOP;
            y = SKY_Y;
        }
    }


    /**
     * Every scenario's craft goes, so the next one starts in a dimension with no live ship: the
     * unloaded-source scenarios below read both counters of the WHOLE dimension, and a hull a sibling
     * left loaded would be in them.
     */
    @org.junit.After
    public void clearCraft() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, 0);
    }

    // --- the unloaded source ------------------------------------------------------------------------
    //
    // The other way a crossing can leave a ship behind: the REGISTRY entry of a source that was not
    // loaded when its blocks were cut. The legs above cover a loaded source, where a physics object is
    // what can be stranded; here no physics object exists at all, so whatever collects a crossing's
    // leftovers has to work without one. An entry left behind has no blocks and nothing loaded behind
    // it, yet it answers position lookups in that world — the opening lookup of the next crossing out
    // of the same place included — and it is persisted with the world.
    //
    // These scenarios turn permanent loading OFF for themselves and put it back when they end: a
    // registered ship that is NOT loaded is their whole arrangement, and held loaded they would
    // silently become copies of the legs above. Their counts are taken before anything asks the world
    // to load a ship, because loading a leftover entry is itself what would collect it. They stand at
    // their own base, apart from the legs above; a crossing hops 160 blocks, wider than an allocated plot.

    private static final int UNLOADED_X = 7000, UNLOADED_Z = 7000;

    /**
     * <p>red-witnessed: only with all four of the source's collectors removed — the mark by name
     * ({@code VSIntegration#releaseShipIfNothingLoaded}), the same-world adoption
     * ({@code VSBridge#adoptOwnRemnant}), the registry walk's blockless clause
     * ({@code WorldServerShipManager#tick}) and the spawn drain's
     * {@code WorldServerShipManager#dropOwnBlocklessRemnant}:
     * "the cut source … was never collected — no `ship_removed` … within 200 ticks", 2026-09-28.
     * Removing the mark alone, or the mark and the adoption, stays GREEN. So this pins the outcome,
     * and cannot say which hand collected: the one this test was written for is not the only one.</p>
     */
    @Test
    public void aCrossingOutOfAnUnloadedSourceLeavesNoRegistryEntry() throws Exception {
        ShipReadiness.letShipsUnload(this::exec,
                "this scenario's subject IS a registered ship nobody has loaded; held loaded, it would"
                + " silently become a copy of the loaded-source legs");
        try {
            // Marked BEFORE the build, because the unload this scenario is built on happens INSIDE it —
            // measured on the gate of 2026-09-22, where a mark taken afterwards saw no `ship_unloaded`
            // for 200 ticks while the counters already read `loaded=0 registry=1`.
            long unloadMark = events.mark();
            String shipId = buildUnloadableShip();
            awaitSourceUnloaded(unloadMark, shipId);

            int registryBefore = queryableShips();
            int loadedBefore = loadedShips();

            // The crossing cuts the hull and pastes a new one under the identity it crossed with, so
            // `shipId` is the SOURCE's removal — nothing removes the arrival. It is aimed by the physics
            // id captured while the craft was loaded: translating the durable name now would force-load
            // the ship and undo the arrangement.
            long crossMark = events.mark();
            String cross = exec("stellurgytest vs ship-repack 0 id " + shipId + " " + UNLOADED_X + " " + BUILD_Y
                    + " " + UNLOADED_Z + " " + (UNLOADED_X + HOP) + " " + SKY_Y + " " + UNLOADED_Z);
            assertTrue("the crossing itself failed, so this test measures nothing: " + cross,
                    Reply.of(cross).ok());
            // Two registry changes, both on later world ticks: the cut source collected, the paste
            // registered when the spawn queue drains. Each linked by its own identity; neither loads.
            events.awaitField(crossMark, "ship_removed", "vsShip", shipId,
                    "a crossing out of an UNLOADED source left its registry entry behind — the cut source"
                            + " " + shipId + " was never collected", WAIT_TICKS);
            events.awaitField(crossMark, "ship_spawned", "stellurgyShip", durableShipId,
                    "the crossed ship was never registered at the destination", WAIT_TICKS);
            // The instrument that makes the OTHER outcome legible, proven to fire on this one: a queued
            // spawn the physics mod refuses is reported only by the `ship_spawn_flood` record, which
            // comes from a redirect declared `require = 0`.
            String floods = events.since(crossMark, "ship_spawn_flood");
            assertTrue("ARRANGEMENT: the arrival's spawn flood must be on the record, and accepted — a"
                            + " missing one means the refusal instrument no longer weaves: " + floods,
                    Events.anyRecordHasAll(floods, "refused", "false"));

            int registryAfter = queryableShips();
            int loadedAfter = loadedShips();

            // Only now prove a ship arrived: after the reads, so the load pump stays out of them.
            requireArrivedAt(crossMark, UNLOADED_X + HOP, SKY_Y);

            assertEquals("a crossing out of an UNLOADED source left its registry entry behind: registered "
                            + "ships went " + registryBefore + " -> " + registryAfter + " across a crossing "
                            + "that moved a single ship (loaded " + loadedBefore + " -> " + loadedAfter + "). "
                            + "An entry with no blocks and nothing loaded behind it still answers every "
                            + "position lookup in this world, the next crossing out of this cell included, "
                            + "and it is written to disk with the world.",
                    registryBefore, registryAfter);
        } finally {
            ShipReadiness.holdShipsLoaded(this::exec, "this scenario's opt-out ends with it");
        }
    }

    /**
     * A blockless record nobody deregisters is COLLECTED by the manager's registry sweep, not merely
     * avoided by the crossing's own hand call.
     *
     * <p><b>The garbage is PLANTED, and the plant is measured on its own call</b>: provoking it means
     * cutting a loaded ship and unloading it inside one tick. The registry size taken inside the
     * planting call proves a record was added; a count read afterwards is already post-collection.
     * Nothing here loads a ship — loading a leftover entry is itself one of the things that collects it.</p>
     */
    @Test
    public void aBlocklessRecordNobodyDeregisteredIsSweptFromTheRegistry() throws Exception {
        ShipReadiness.letShipsUnload(this::exec,
                "the planted record must stand in a world where nothing holds ships loaded");
        try {
            int registryBefore = queryableShips();

            long plantMark = events.mark();
            String planted = exec("stellurgytest vs strand-blockless-record 0 "
                    + UNLOADED_X + " " + BUILD_Y + " " + UNLOADED_Z);
            assertTrue("the fault injection itself failed, so this leg measures nothing: " + planted,
                    Reply.of(planted).ok());
            assertEquals("the plant must actually have put a record in the registry — read on the "
                            + "planting call itself, before any tick could collect it. Without this the "
                            + "assertion below is satisfied by a plant that never happened: " + planted,
                    registryBefore + 1, extractInt(planted, "countAfterAdd"));

            // Linked on the registry's own REMOVAL of the planted record, by its uuid — a count could
            // not say WHICH entry went.
            events.awaitField(plantMark, "ship_removed", "vsShip", Reply.of(planted).text("uuid"),
                    "the manager's registry sweep never collected the planted blockless record;"
                            + " registry went " + registryBefore + " -> " + queryableShips()
                            + "; planted=" + planted, WAIT_TICKS);
        } finally {
            ShipReadiness.holdShipsLoaded(this::exec, "this scenario's opt-out ends with it");
        }
    }

    /**
     * Build the unloaded-source craft and answer its PHYSICS id, taken while it is still loaded: the
     * durable->physics bridge repairs its index by reading flight computers, which force-loads the
     * ship it is asked about, so resolving the id later would undo the arrangement it is needed for.
     */
    private String buildUnloadableShip() throws Exception {
        long buildMark = events.mark();
        String coords = placeFixture(FixtureSite.openAir(0, UNLOADED_X, UNLOADED_Z), "with-pilot-seat");
        String asm = exec("stellurgytest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));
        durableShipId = ShipIdentity.nameFromAssembly(asm);
        events.awaitField(buildMark, "ship_spawned", "stellurgyShip", durableShipId,
                "the ship never entered VS's registry: " + counters(), WAIT_TICKS);
        return ShipIdentity.physicsIdOf(this::exec, 0, durableShipId);
    }

    /**
     * Wait for the craft to be UNLOADED — the substrate's own {@code unload()}, by physics id — and
     * then read that it HOLDS: the mark precedes the build, so the window can contain a
     * load/unload/load sequence, and a hull loaded now would let the crossing cut a loaded source.
     */
    private void awaitSourceUnloaded(long mark, String shipId) throws Exception {
        events.awaitField(mark, "ship_unloaded", "vsShip", shipId,
                "the source ship never unloaded, so this test would measure the loaded-source"
                        + " arrangement instead of its own: " + counters(), WAIT_TICKS);
        assertEquals("the source unloaded and was loaded again, so this is the loaded-source"
                + " arrangement and not this scenario's: " + counters(), 0, loadedShips());
    }

    /**
     * Wait for the crossing's arrival to be REGISTERED and then UNLOADED, pump a load, and read where
     * it stands. Nothing holds ships loaded here, so a fresh hull is loaded by its spawn and dropped by
     * the load controller straight after; the pump must follow that drop, and the read must follow the
     * pump with nothing between — a hull is resident for about a tick after a load.
     */
    private void requireArrivedAt(long mark, int x, int y) throws Exception {
        String spawned = events.awaitRecordWithField(mark, "ship_spawned", "stellurgyShip", durableShipId,
                "the crossed ship was never registered at the destination: " + counters(), WAIT_TICKS);
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
                        + " this scenario — before a pump can load it for the read: " + counters(),
                WAIT_TICKS);
        exec("stellurgytest vs load-ships 0");
        // READ INTO A LOCAL: Java evaluates an assertion's MESSAGE before its condition, and the
        // message's probe calls would spend the tick the hull is resident (measured 2026-09-22).
        boolean arrived = ShipIdentity.aLoadedShipIsAt(this::exec, 0, x, y, UNLOADED_Z, POSE_TOLERANCE);
        assertTrue("the arrival was registered, dropped and pumped, but no loaded ship stands within "
                        + POSE_TOLERANCE + " of " + x + "," + y + "," + UNLOADED_Z + ": " + counters()
                        + " | loaded hulls now: " + exec("stellurgytest vs ships-loaded 0"),
                arrived);
    }

    // --- the invariant ------------------------------------------------------------------------------

    /** Cross the ship at the source and assert the world holds as many ships after as before, by BOTH counters. */
    private void crossConserving(int sx, int sy, int dx, int dy, String what) throws Exception {
        int loadedBefore = loadedShips();
        int registryBefore = queryableShips();

        // Marked before the crossing: it CUTS the hull and pastes a new one, so the arrival is a
        // fresh registry add — announced once, with the same durable name and a new physics id.
        long crossMark = events.mark();
        String cross = repack(sx, sy, dx, dy);
        assertTrue(what + " itself failed, so this leg measures nothing: " + cross,
                Reply.of(cross).ok());
        requireLoadedShipAt(crossMark, dx, dy,
                "the crossed ship never arrived at " + dx + "," + dy + "; " + what + "=" + cross);

        int loadedAfter = loadedShips();
        int registryAfter = queryableShips();
        assertEquals(what + " left a ship OBJECT behind in the world it departed: loaded ships went "
                        + loadedBefore + " -> " + loadedAfter + " across a crossing that moved a single "
                        + "ship, while the registry went " + registryBefore + " -> " + registryAfter + ". "
                        + "A loaded ship the registry does not hold is never destroyed and never "
                        + "unloaded, and a crossing leaves one every time.",
                loadedBefore, loadedAfter);
        assertEquals(what + " left a registry ENTRY behind in the world it departed: registered ships "
                        + "went " + registryBefore + " -> " + registryAfter + " (loaded " + loadedBefore
                        + " -> " + loadedAfter + ").",
                registryBefore, registryAfter);
    }

    // --- arrangement --------------------------------------------------------------------------------

    /** Build one tier-2 ship at {@code (baseX, BUILD_Y, BASE_Z)} and wait until VS has really created it. */
    private void buildShipAt(int baseX) throws Exception {
        // Marked before the assemble: the registry add is made inside it, and is announced once.
        long buildMark = events.mark();
        String coords = placeFixture(FixtureSite.openAir(0, baseX, BASE_Z), "with-pilot-seat");
        String asm = exec("stellurgytest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));
        durableShipId = ShipIdentity.nameFromAssembly(asm);
        // The registry-count comparison that stood here is gone with the positional poll below it:
        // "the count went up" is a statement about the dimension, and the add itself names the craft.
        requireLoadedShipAt(buildMark, baseX, BUILD_Y,
                "the craft this leg builds must be a loaded ship before anything is crossed");
    }

    /**
     * This craft's DURABLE name. Not its physics id: this class crosses the same ship three times in
     * a row and every crossing mints a new physics id, so the name is the only handle that spans the
     * run — which is also why the cut is aimed with a freshly translated id each time.
     */
    private String durableShipId;

    private String repack(int sx, int sy, int dx, int dy) throws Exception {
        // The crossing CUTS a ship, so it is told which one. The positional form resolves the yard as
        // "whatever craft is nearest", and this class exists to prove no ship is LEFT BEHIND — so the
        // leftovers it hunts for are precisely what such a lookup would reach for.
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableShipId);
        return exec("stellurgytest vs ship-repack 0 id " + shipId + " " + sx + " " + sy + " " + BASE_Z
                + " " + dx + " " + dy + " " + BASE_Z);
    }

    // --- observation --------------------------------------------------------------------------------

    private int loadedShips() throws Exception {
        return extractInt(exec("stellurgytest vs ship-count 0"), "count");
    }

    private int queryableShips() throws Exception {
        return extractInt(exec("stellurgytest vs ship-count-all 0"), "count");
    }

    /** Both counters together: the pair is the diagnosis, either one alone is just a number. */
    private String counters() throws Exception {
        return "[loaded=" + loadedShips() + " registry=" + queryableShips() + "]";
    }

    /**
     * Is there a loaded ship whose own pose is at {@code (x,y,BASE_Z)}?
     *
     * <p>Answered by asking EVERY loaded ship where it is. The previous form asked the world which
     * ship was nearest the spot and then compared that one ship's pose — an unbounded lookup with a
     * filter on its single answer, so a hull sitting exactly here was invisible whenever the lookup
     * preferred another. Same claim, no lookup that can pick the wrong craft to test.</p>
     */
    private boolean shipIsAt(int x, int y) throws Exception {
        return ShipIdentity.aLoadedShipIsAt(this::exec, 0, x, y, BASE_Z, POSE_TOLERANCE);
    }

    /**
     * Wait for THIS craft's hull to be registered and then LOADED, and assert WHERE it stands.
     *
     * <p>Three steps, and the split is the point: the poll this replaces asked one positional
     * question — "is a loaded ship at this pose yet" — and so could not tell a hull that was never
     * registered from one registered and never loaded from one loaded in the wrong place. Each of
     * those is a different defect and this class exists to tell crossings' defects apart.</p>
     *
     * <p>Both links are the substrate's own: {@code ship_spawned} is written from
     * {@code QueryableShipData.addShip} and carries the durable Stellurgy id the hull was bound with, so
     * the first wait names THIS craft; the physics id it also carries is what the second wait uses,
     * so "the hull became loaded" is asked about the very hull the first wait found rather than
     * about whatever else is in the dimension. Every crossing mints a NEW physics id, which is
     * exactly why the durable name is the handle and the physics id is read out of the record.</p>
     *
     * <p>The pose is then an ASSERTION rather than a wait, because by this point production has
     * said the ship is up: a hull that is loaded and standing somewhere else is a finding, not a
     * reason to keep looking.</p>
     */
    private void requireLoadedShipAt(long mark, int x, int y, String what) throws Exception {
        String spawned = events.awaitRecordWithField(mark, "ship_spawned", "stellurgyShip", durableShipId,
                what + " — no hull was ever registered for this craft", WAIT_TICKS);
        String vsShip = Events.text(spawned, "vsShip");
        events.awaitField(mark, "ship_loaded", "vsShip", vsShip,
                what + " — hull " + vsShip + " reached the registry and never became a loaded ship",
                WAIT_TICKS);
        assertTrue(what + " — hull " + vsShip + " is loaded, but no loaded ship stands within "
                        + POSE_TOLERANCE + " of " + x + "," + y + "," + BASE_Z + ": " + counters(),
                shipIsAt(x, y));
    }

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());


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

    // --- the announcements ----------------------------------------------------------------------------
    //
    // Becoming a ship, and stopping being one, is announced exactly once per transition — and the
    // announcement says WHICH transition it was.
    //
    // Why an announcement rather than a question: anything that must act at the moment a craft
    // becomes a ship cannot find that moment by asking "is it named yet" over and over; each subsystem
    // that polls arrives at its own private answer about which tick the craft started existing on.
    // Why the count is the property, not the state: an edge leaves nothing behind in the world it
    // changes — afterwards the ship is simply named, whether the edge fired once, three times, or
    // never. Why the causes must be distinguishable: a consumer minting a durable record for a new
    // vessel wants only the first transition; if an assembly and a crossing announce the same thing,
    // it mints a second record for a craft that already had one.
    //
    // Each window is read once, after the LAST transition it is about has been announced: a duplicate
    // is published in the same manager tick as the one it duplicates, so by the time the awaited
    // record is in the log its twin is too. Their bases are their own, apart from every leg above.

    private static final int NAMING_Z = 9400;
    /** One craft per announcement scenario, each on its own X lane, so neither can see the other's. */
    private static final int NAMING_CYCLE_X = 9400, NAMING_CROSS_X = 9800;

    /**
     * A craft is built, dropped, and fetched back: ASSEMBLED, then UNLOADED, then LOADED — each once.
     *
     * <p>red-witnessed: with the UNLOADED note ({@code WorldServerShipManager#loadAndUnloadShips} at
     * {@code noteLifecycle(physicsObject.getShipData(), ShipLifecycleEvent.Cause.UNLOADED);}) removed, "dropping the ship object must be announced"
     * fails with no such record within 200 ticks; with the LOADED note ({@code WorldServerShipManager#loadAndUnloadShips} at
     * {@code noteLifecycle(toLoad, ShipLifecycleEvent.Cause.LOADED);}) made twice, "coming back must be announced exactly once", 2026-09-29.
     * One break per remaining verdict, 2026-09-30: the spawn note at {@code WorldServerShipManager#spawnNewShips} at
     * {@code noteLifecycle(toSpawn, spawnData.cause);} removed fails "a craft that has just been built must be announced"; the LOADED
     * note removed fails "coming back must be announced"; the UNLOADED note made twice fails "exactly
     * once"; a PASTED note added beside the LOADED one fails "nothing here was cut and pasted"; a
     * DESTROYED note added beside the UNLOADED one fails "an unloaded craft still EXISTS".</p>
     */
    @Test
    public void buildingDroppingAndFetchingBackAreThreeDistinctAnnouncements() throws Exception {

        long mark = events.mark();
        String shipId = buildAnnouncedShipAt(NAMING_CYCLE_X);
        awaitAnnounced(mark, shipId, "ASSEMBLED", "a craft that has just been built must be announced");

        // Nothing holds a ship loaded on a headless server once permanent loading is off: there is no
        // player for the world's own pass to measure a distance to, so it drops the craft by itself.
        ShipReadiness.letShipsUnload(this::exec, "the un-naming half is the subject: the craft has to be"
                + " dropped by the world's own pass");
        String window;
        try {
            awaitAnnounced(mark, shipId, "UNLOADED", "dropping the ship object must be announced");
        } finally {
            ShipReadiness.holdShipsLoaded(this::exec, "the LOADED edge is the last subject: the craft has"
                    + " to be fetched back — and this scenario's opt-out ends with it");
        }
        exec("stellurgytest vs load-ships 0");
        window = awaitAnnounced(mark, shipId, "LOADED", "coming back must be announced");

        assertEquals("a craft that has just been built must be announced as ASSEMBLED exactly once: "
                + window, 1, announced(window, shipId, "ASSEMBLED"));
        assertEquals("dropping the ship object must be announced exactly once: " + window,
                1, announced(window, shipId, "UNLOADED"));
        assertEquals("coming back must be announced exactly once: " + window,
                1, announced(window, shipId, "LOADED"));
        assertEquals("nothing here was cut and pasted - a consumer that mints a durable record only for"
                + " a genuinely new vessel would mint nothing at all if a build were reported as a"
                + " paste: " + window, 0, announced(window, shipId, "PASTED"));
        assertEquals("an unloaded craft still EXISTS - it is registered and on disk and will be back."
                + " Reporting it as destroyed would tell every consumer holding something durable for"
                + " this vessel to throw it away: " + window, 0, announced(window, shipId, "DESTROYED"));
    }

    /**
     * A craft that crosses is announced as PASTED, never as a second birth.
     *
     * <p>red-witnessed: with {@code WorldServerShipManager.spawnNewShips} ({@code WorldServerShipManager#spawnNewShips} at
     * {@code noteLifecycle(toSpawn, spawnData.cause);}) noting every spawn as {@code ASSEMBLED}, "no `ship_lifecycle` carrying … cause =
     * PASTED was recorded within 200 ticks", 2026-09-29; a PASTED spawn noted twice fails "announced as
     * PASTED exactly once", and one also noted as ASSEMBLED fails "a crossing is not a new build",
     * 2026-09-30.</p>
     */
    @Test
    public void aCrossingIsAnnouncedAsAPasteAndNotAsANewBuild() throws Exception {

        long mark = events.mark();
        String shipId = buildAnnouncedShipAt(NAMING_CROSS_X);
        ArrangementFailure.arranged(() -> awaitAnnounced(mark, shipId, "ASSEMBLED",
                "the craft must exist before it can cross"));

        // The production crossing: the blocks are cut out and pasted elsewhere, and the craft is
        // re-registered around them. Told WHICH craft to cut; the source pose is only where the
        // riders are gathered.
        ShipInfo source = ShipInfo.byId(this::exec, 0, shipId);
        String repack = exec("stellurgytest vs ship-repack 0 id " + shipId + " "
                + (int) source.x + " " + (int) source.y + " " + (int) source.z + " "
                + (NAMING_CROSS_X + HOP) + " " + FixtureSite.OPEN_AIR_Y + " " + NAMING_Z);
        requireArranged("the crossing must actually run, or nothing below is a paste: " + repack,
                Reply.of(repack).ok());
        // The identity the craft came out under, as the crossing itself reports it.
        String crossedId = Reply.of("stellurgytest vs ship-repack", repack).text("shipUuid");

        String window = awaitAnnounced(mark, crossedId, "PASTED",
                "a craft re-registered around pasted blocks must be announced as PASTED");
        assertEquals("a craft re-registered around pasted blocks must be announced as PASTED exactly"
                + " once: " + window, 1, announced(window, crossedId, "PASTED"));
        assertEquals("a crossing is not a new build. This is the distinction the whole cause enum exists"
                + " for: reported as ASSEMBLED, a vessel would acquire a second birth record every time"
                + " it crossed. The crossing keeps the identity when it can (" + shipId + " -> "
                + crossedId + "), so the build's own ASSEMBLED is the only one this id may carry: "
                + window,
                shipId.equals(crossedId) ? 1 : 0, announced(window, crossedId, "ASSEMBLED"));
    }

    /**
     * Wait for {@code cause} to be announced for {@code shipId} since {@code mark}, and answer the
     * WHOLE window of announcements at that moment — the reply every count above is read from.
     */
    private String awaitAnnounced(long mark, String shipId, String cause, String what) throws Exception {
        events.awaitRecordWithFields(mark, "ship_lifecycle", what, WAIT_TICKS,
                "ship", shipId, "cause", cause);
        return events.since(mark, "ship_lifecycle");
    }

    /** How many announcements of {@code cause} the window holds for {@code shipId}. */
    private static int announced(String window, String shipId, String cause) {
        return Events.recordsWhereAll(window, "ship", shipId, "cause", cause).size();
    }

    /** Build and assemble a craft on its own lane, and answer its physics identity once it is named. */
    private String buildAnnouncedShipAt(int baseX) throws Exception {
        String asm = RocketFixture.assembleAt(FixtureSite.openAir(0, baseX, NAMING_Z), this::exec,
                "with-pilot-seat", 4, 12, "the craft whose naming edges are counted stands in this volume");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                Reply.of(asm).integer("rocketCount") == 0);
        return ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events, 0,
                ShipIdentity.nameFromAssembly(asm), WAIT_TICKS));
    }
}
