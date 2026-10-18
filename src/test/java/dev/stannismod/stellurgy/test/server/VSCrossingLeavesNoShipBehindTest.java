package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A per-ship crossing MOVES a ship, so it must not ADD one to the world it left.
 *
 * <p>A crossing snapshots the source ship's subspace shipyard, cuts those blocks, pastes them elsewhere
 * and re-assembles the craft there. One ship goes away exactly as one appears. Both legs assert that
 * invariant, and they assert it through the RECORDS of the crossing itself, never through a count of
 * the world's ships: in the window from the crossing to its arrival,</p>
 * <ul>
 *   <li>the source's registration ends as {@code DEPARTED} — the craft left, it was neither kept nor
 *       destroyed;</li>
 *   <li>exactly ONE craft is created — one {@code ship_lifecycle} naming with cause {@code ASSEMBLED}
 *       or {@code PASTED}, and it is this craft's {@code PASTED};</li>
 *   <li>the registry takes back exactly what it gives — as many {@code ship_removed} as
 *       {@code ship_spawned}, so no entry is left behind;</li>
 *   <li>the engine let go of the source's ship OBJECT before the paste named the arrival — a
 *       {@code ship_released} for its id, ahead of the {@code PASTED}. The crossing re-assembles the
 *       craft under the identity it crossed with, so an object left loaded with no registration behind it
 *       would answer to the arrival's own id; the release is the object's own announcement, and its
 *       absence is that object;</li>
 *   <li>and the arrival is this craft, loaded, standing where it was sent — read by its own id.</li>
 * </ul>
 *
 * <p><b>The enabling condition is asserted, not assumed.</b> A ship OBJECT can only be stranded if the
 * source was loaded when its blocks were cut, so each leg links on the source being loaded before it
 * crosses.</p>
 *
 * <p><b>And the announcements that go with those transitions.</b> The scenarios at the end count what
 * production ANNOUNCES as a craft is built, dropped, fetched back and crossed — one
 * {@code ship_lifecycle} record per transition, saying which one it was. An announcement is what every
 * consumer that acts at those moments (the mass recompute, a durable record minted at birth) is told.</p>
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

    /** The instrument behind every {@code ship_lifecycle} record. */
    private static final String LIFECYCLE_INSTRUMENT = "server_bus_ship_lifecycle";
    /** The instrument behind every {@code ship_spawned} / {@code ship_removed} record. */
    private static final String REGISTRY_INSTRUMENT = "ship_registry_events";

    /**
     * How much WORLD a bounded wait is allowed: 200 server ticks, the ten seconds the old
     * {@code 40 x 250 ms} meant on an idle box. On the SERVER's clock, because what these wait for —
     * a queued assembly being drained, a queued load being served — is driven by the server tick
     * loop itself, and the worlds involved are often the ones that have not started ticking yet.
     */
    private static final int WAIT_TICKS = 200;

    /** The defect in one crossing: the ship left behind in the world the crossing departed. */
    @Test
    @RedWitness(verdict = "never let go of the source's ship OBJECT", member = "WorldServerShipManager#tick",
            at = "physicsObject.destroyShip(); noteReleased(physicsObject.getShipData());",
            replacedBy = "physicsObject.destroyShip();",
            fails = "the crossing never let go of the source's ship OBJECT",
            on = "2026-10-14")
    public void aCrossingDoesNotLeaveAShipInTheWorldItLeft() throws Exception {

        // This leg measures what a crossing strands, which as an OBJECT can only exist if the source
        // is loaded when it is cut — which `buildShipAt` establishes on the substrate's own records.
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


    /** The mark taken before this scenario's build. */
    private long buildMark;

    /**
     * This scenario's craft goes when it ends, by the id its latest naming carries — the build's, or
     * the last crossing's.
     */
    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (durableShipId != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, durableShipId);
            durableShipId = null;
        }
    }

    // --- the unloaded source ------------------------------------------------------------------------
    //
    // The other way a crossing can leave a ship behind: the REGISTRY entry of a source that was not
    // loaded when its blocks were cut. The legs above cover a loaded source; here no physics object
    // exists at all, so whatever collects a crossing's leftovers has to work without one. An entry left
    // behind has no blocks and nothing loaded behind it, yet it answers position lookups in that world
    // — the opening lookup of the next crossing out of the same place included — and it is persisted
    // with the world.
    //
    // These scenarios turn permanent loading OFF for themselves and put it back when they end: a
    // registered ship that is NOT loaded is their whole arrangement, and held loaded they would
    // silently become copies of the legs above. They stand at their own base, apart from the legs
    // above; a crossing hops 160 blocks, wider than an allocated plot.

    private static final int UNLOADED_X = 7000, UNLOADED_Z = 7000;

    @LegacyWitness(prose = "only with all four of the source's collectors removed — the mark by name"
            + " ({@code VSIntegration#discardShip}), the same-world adoption"
            + " ({@code VSBridge#adoptOwnRemnant}), the registry walk's blockless clause"
            + " ({@code WorldServerShipManager#tick}) and the spawn drain's"
            + " {@code WorldServerShipManager#dropOwnBlocklessRemnant}:"
            + " \"the cut source … was never collected — no `ship_removed` … within 200 ticks\", 2026-09-28."
            + " Removing the mark alone, or the mark and the adoption, stays GREEN. So this pins the outcome,"
            + " and cannot say which hand collected: the one this test was written for is not the only one.")
    @Test
    public void aCrossingOutOfAnUnloadedSourceLeavesNoRegistryEntry() throws Exception {
        ShipReadiness.letShipsUnload(c -> arrange(c).raw(),
                "this scenario's subject IS a registered ship nobody has loaded; held loaded, it would"
                + " silently become a copy of the loaded-source legs");
        try {
            // Marked BEFORE the build, because the unload this scenario is built on happens INSIDE it —
            // measured on the gate of 2026-09-22, where a mark taken afterwards saw no `ship_unloaded`
            // for 200 ticks.
            long unloadMark = events.mark();
            String shipId = buildUnloadableShip();
            awaitSourceUnloaded(unloadMark, shipId);

            // The crossing cuts the hull and pastes it again under the identity it crossed with. It is
            // aimed by the physics id the assembly announced: translating the durable name now would
            // force-load the ship and undo the arrangement.
            long crossMark = events.mark();
            String cross = act("stellurgytest vs ship-repack 0 id " + shipId + " " + UNLOADED_X + " " + BUILD_Y
                    + " " + UNLOADED_Z + " " + (UNLOADED_X + HOP) + " " + SKY_Y + " " + UNLOADED_Z).raw();
            assertTrue("the crossing itself failed, so this test measures nothing: " + cross,
                    Reply.of(cross).ok());
            // Two registry changes, both on later world ticks: the cut source collected, the paste
            // registered when the spawn queue drains. Each linked by its own identity; neither loads.
            events.awaitField(crossMark, "ship_removed", "vsShip", shipId,
                    "a crossing out of an UNLOADED source left its registry entry behind — the cut source"
                            + " " + shipId + " was never collected", WAIT_TICKS);
            String spawned = events.awaitRecordWithField(crossMark, "ship_spawned", "stellurgyShip", durableShipId,
                    "the crossed ship was never registered at the destination", WAIT_TICKS);
            // The instrument that makes the OTHER outcome legible, proven to fire on this one: a queued
            // spawn the physics mod refuses is reported only by the `ship_spawn_flood` record, which
            // comes from a redirect declared `require = 0`.
            String floods = events.since(crossMark, "ship_spawn_flood");
            assertTrue("ARRANGEMENT: the arrival's spawn flood must be on the record, and accepted — a"
                            + " missing one means the refusal instrument no longer weaves: " + floods,
                    Events.anyRecordHasAll(floods, "refused", "false"));

            // The registry's own account of this crossing, read before anything loads a ship: every
            // entry it added is matched by one it removed.
            String added = events.since(crossMark, "ship_spawned");
            String removed = events.since(crossMark, "ship_removed");
            Events.assertInstrumentRan(removed, REGISTRY_INSTRUMENT,
                    "the registry gave back every entry the crossing made it take");

            // Only now prove a ship arrived: after the reads, so the load stays out of them.
            requireArrivedAt(crossMark, Events.text(spawned, "vsShip"), UNLOADED_X + HOP, SKY_Y);

            assertEquals("a crossing out of an UNLOADED source left its registry entry behind: the registry"
                            + " took " + Events.records(added).size() + " entr(ies) and gave back "
                            + Events.records(removed).size() + " across a crossing that moved a single"
                            + " ship. An entry with no blocks and nothing loaded behind it still answers every"
                            + " position lookup in this world, the next crossing out of this cell included,"
                            + " and it is written to disk with the world. added=" + added + " removed=" + removed,
                    Events.records(added).size(), Events.records(removed).size());
        } finally {
            ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "this scenario's opt-out ends with it");
        }
    }

    /**
     * A blockless record nobody deregisters is COLLECTED by the manager's registry sweep, not merely
     * avoided by the crossing's own hand call.
     *
     * <p><b>The garbage is PLANTED, and the plant is measured on its own call</b>: provoking it means
     * cutting a loaded ship and unloading it inside one tick. Whether the registry holds the planted
     * record, read inside the planting call, proves a record was added; a read afterwards is already
     * post-collection. Nothing here loads a ship — loading a leftover entry is itself one of the things
     * that collects it.</p>
     */
    @Test
    public void aBlocklessRecordNobodyDeregisteredIsSweptFromTheRegistry() throws Exception {
        ShipReadiness.letShipsUnload(c -> arrange(c).raw(),
                "the planted record must stand in a world where nothing holds ships loaded");
        try {
            long plantMark = events.mark();
            String planted = arrange("stellurgytest vs strand-blockless-record 0 "
                    + UNLOADED_X + " " + BUILD_Y + " " + UNLOADED_Z).raw();
            Reply plant = Reply.of("stellurgytest vs strand-blockless-record", planted);
            assertTrue("the fault injection itself failed, so this leg measures nothing: " + planted,
                    plant.ok());
            assertTrue("the plant must actually have put its record in the registry — read on the"
                            + " planting call itself, before any tick could collect it. Without this the"
                            + " link below is satisfied by a plant that never happened: " + planted,
                    plant.bool("registered"));

            // Linked on the registry's own REMOVAL of the planted record, by its uuid.
            events.awaitField(plantMark, "ship_removed", "vsShip", plant.text("uuid"),
                    "the manager's registry sweep never collected the planted blockless record;"
                            + " planted=" + planted, WAIT_TICKS);
        } finally {
            ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "this scenario's opt-out ends with it");
        }
    }

    /**
     * A ship declared finished while only a BACKGROUND load request holds it is collected by the
     * registry sweep, and the collection is announced — not left standing until something loads it.
     *
     * <p>The arrangement is the request itself: the craft is let unload, then the hold is put back, and
     * the hold renews a background load for every unloaded ship each tick without ever completing one.
     * The request is linked on its own record before the craft is declared finished, so the scenario
     * cannot pass on a craft the sweep reached before any request stood.</p>
     */
    @Test
    @Pins("JUMP-16")
    @RedWitness(member = "WorldServerShipManager#tick",
            at = "if (loadedShips.containsKey(data.getUuid()) || loadQueue.contains(data.getUuid())) {",
            replacedBy = "if (isShipInUse(data.getUuid())) {",
            fails = "a ship declared finished while a background load request held it was never collected",
            on = "2026-10-14")
    public void aShipFinishedWhileABackgroundLoadHoldsItIsCollected() throws Exception {
        ShipReadiness.letShipsUnload(c -> arrange(c).raw(),
                "the craft must be UNLOADED before the hold renews a background load for it");
        String shipId;
        long requestMark;
        try {
            long unloadMark = events.mark();
            shipId = buildAt(site()).physicsId;
            awaitSourceUnloaded(unloadMark, shipId);
        } finally {
            requestMark = events.mark();
            ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "the hold IS the background load request");
        }
        events.awaitRecordWithFields(requestMark, "ship_background_load_requested",
                "the hold never asked for a background load of the unloaded craft, so this scenario would"
                        + " measure a sweep no request stood in the way of", WAIT_TICKS, "ship", shipId);

        long finishMark = events.mark();
        String finish = arrange("stellurgytest vs destroy-ship 0 " + shipId).raw();
        assertTrue("the craft must be registered to be declared finished: " + finish,
                Reply.of(finish).ok() && Reply.of(finish).bool("found"));
        events.awaitRecordWithFields(finishMark, "ship_lifecycle",
                "a ship declared finished while a background load request held it was never collected —"
                        + " it stays registered, holding its claim, until something loads it", WAIT_TICKS,
                "ship", shipId, "cause", "DESTROYED");
        durableShipId = null;
    }

    /**
     * Build the unloaded-source craft and answer its PHYSICS id, as the assembly's naming announced it
     * — taken off the record, so nothing here force-loads the ship.
     */
    private String buildUnloadableShip() throws Exception {
        ShipBirth birth = buildAt(FixtureSite.openAir(0, UNLOADED_X, UNLOADED_Z));
        events.awaitField(buildMark, "ship_spawned", "stellurgyShip", durableShipId,
                "the ship never entered VS's registry", WAIT_TICKS);
        return birth.physicsId;
    }

    /**
     * Wait for the craft to be UNLOADED — the substrate's own {@code unload()}, by physics id — and
     * then read that it HOLDS: the mark precedes the build, so the window can contain a
     * load/unload/load sequence, and a hull loaded now would let the crossing cut a loaded source.
     */
    private void awaitSourceUnloaded(long mark, String shipId) throws Exception {
        events.awaitField(mark, "ship_unloaded", "vsShip", shipId,
                "the source ship never unloaded, so this test would measure the loaded-source"
                        + " arrangement instead of its own", WAIT_TICKS);
        assertTrue("the source unloaded and was loaded again, so this is the loaded-source"
                + " arrangement and not this scenario's", !ShipInfo.loadedIn(c -> read(c).raw(), 0, shipId));
    }

    /**
     * Wait for the crossing's arrival {@code arrivalKey} to be UNLOADED, ask for it by its id, and read
     * where it stands. Nothing holds ships loaded here, so a fresh hull is loaded by its spawn and
     * dropped by the load controller straight after; the load must follow that drop, and the read must
     * follow the load with nothing between — a hull is resident for about a tick after a load.
     */
    private void requireArrivedAt(long mark, String arrivalKey, int x, int y) throws Exception {
        String spawned = events.awaitRecordWithField(mark, "ship_spawned", "vsShip", arrivalKey,
                "the crossed ship was never registered at the destination", WAIT_TICKS);
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
                        + " this scenario — before it can be asked for and read",
                WAIT_TICKS);
        arrange("stellurgytest vs load-ship 0 " + arrivalKey).requireOk("ask for the arrival by its id");
        // READ INTO A LOCAL: Java evaluates an assertion's MESSAGE before its condition, and a probe
        // call in the message would spend the tick the hull is resident (measured 2026-09-22).
        String info = read("stellurgytest vs ship-info 0 id " + arrivalKey).raw();
        assertTrue("the arrival " + arrivalKey + " was registered, dropped and asked for, but it is not"
                        + " loaded within " + POSE_TOLERANCE + " of " + x + "," + y + "," + UNLOADED_Z
                        + ": " + info,
                standsAt(info, x, y, UNLOADED_Z));
    }

    // --- the invariant ------------------------------------------------------------------------------

    /**
     * Cross the ship at the source and assert, off the crossing's own records, that it moved one ship
     * and added none: the source departed, one craft was created and it is this one's paste, and the
     * registry gave back every entry it took.
     */
    private void crossConserving(int sx, int sy, int dx, int dy, String what) throws Exception {
        String srcId = ShipIdentity.physicsIdOf(c -> read(c).raw(), 0, durableShipId);

        long crossMark = events.mark();
        String cross = act("stellurgytest vs ship-repack 0 id " + srcId + " " + sx + " " + sy + " " + BASE_Z
                + " " + dx + " " + dy + " " + BASE_Z).raw();
        assertTrue(what + " itself failed, so this leg measures nothing: " + cross,
                Reply.of(cross).ok());
        String arrived = requireLoadedShipAt(crossMark, dx, dy,
                "the crossed ship never arrived at " + dx + "," + dy + "; " + what + "=" + cross);
        events.awaitRecordWithFields(crossMark, "ship_lifecycle",
                what + " must end the source's registration as a departure", WAIT_TICKS,
                "ship", srcId, "edge", "unnamed", "cause", "DEPARTED");

        String window = events.since(crossMark, "ship_lifecycle");
        Events.assertInstrumentRan(window, LIFECYCLE_INSTRUMENT, what + " created no craft but the one it carried");
        List<String> created = new java.util.ArrayList<>(Events.recordsWhereAll(window, "cause", "ASSEMBLED"));
        created.addAll(Events.recordsWhereAll(window, "cause", "PASTED"));
        assertEquals(what + " created " + created.size() + " craft where it moved one — a ship it made"
                        + " beside the one it carried stands in the world it left or the one it reached: "
                        + created,
                1, created.size());
        assertTrue(what + " created a craft, but not as this one's paste: " + created,
                durableShipId.equals(Events.text(created.get(0), "durable"))
                        && "PASTED".equals(Events.text(created.get(0), "cause"))
                        && arrived.equals(Events.text(created.get(0), "ship")));
        assertEquals(what + " announced the source as DESTROYED, where it departed: " + window,
                0, Events.recordsWhereAll(window, "ship", srcId, "cause", "DESTROYED").size());

        // The source's OBJECT, not only its registration: the crossing re-assembles under the identity
        // it crossed with, so an object left loaded would answer to the arrival's own id. What tells the
        // two apart is the engine letting go of the source's object BEFORE the paste names the arrival.
        // No instrument check of its own: the release handler fires only on a release, so its absence is
        // the defect and cannot also be the proof of a listener. That the recorder is subscribed is the
        // lifecycle check above — both handlers are static members of the one class registered on the bus.
        String released = events.since(crossMark, "ship_released");
        List<String> sourceReleased = Events.recordsWhereAll(released, "ship", srcId);
        assertTrue(what + " never let go of the source's ship OBJECT — it stays loaded and ticking under the"
                        + " identity the arrival answers to: released=" + released,
                !sourceReleased.isEmpty());
        assertTrue(what + " let go of the source's ship object only AFTER the paste named the arrival, so"
                        + " for that stretch two objects answered to one identity: released=" + sourceReleased
                        + " pasted=" + created.get(0),
                Events.number(sourceReleased.get(0), "seq") < Events.number(created.get(0), "seq"));

        String added = events.since(crossMark, "ship_spawned");
        String removed = events.since(crossMark, "ship_removed");
        Events.assertInstrumentRan(removed, REGISTRY_INSTRUMENT,
                what + " made the registry give back every entry it took");
        assertEquals(what + " left a registry ENTRY behind in the world it departed: the registry took "
                        + Events.records(added).size() + " entr(ies) and gave back "
                        + Events.records(removed).size() + ". added=" + added + " removed=" + removed,
                Events.records(added).size(), Events.records(removed).size());
    }

    // --- arrangement --------------------------------------------------------------------------------

    /** Build one tier-2 ship at {@code (baseX, BUILD_Y, BASE_Z)} and wait until VS has really created it. */
    private void buildShipAt(int baseX) throws Exception {
        buildAt(FixtureSite.openAir(0, baseX, BASE_Z));
        requireLoadedShipAt(buildMark, baseX, BUILD_Y,
                "the craft this leg builds must be a loaded ship before anything is crossed");
    }

    /** Lay and press a with-pilot-seat craft at {@code site}; the craft is this scenario's from here on. */
    private ShipBirth buildAt(FixtureSite site) throws Exception {
        int[] bp = RocketFixture.placeAt(site, c -> arrange(c).raw(), "with-pilot-seat", 4, 12,
                "the craft this scenario builds stands in this volume");
        buildMark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        durableShipId = ShipIdentity.nameFromAssembly(asm);
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, bp, asm, WAIT_TICKS,
                "the craft this scenario crosses");
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());
        return birth;
    }

    /**
     * This craft's DURABLE name. Not its physics id: this class crosses the same ship three times in
     * a row, so the name is the handle that spans the run — which is also why the cut is aimed with a
     * freshly translated id each time.
     */
    private String durableShipId;

    // --- observation --------------------------------------------------------------------------------

    /** Whether a {@code ship-info ... id} reply says that ship is loaded and stands within tolerance of the point. */
    private static boolean standsAt(String shipInfoReply, double x, double y, double z) {
        if (!ShipInfo.isLoaded(shipInfoReply)) {
            return false;
        }
        ShipInfo at = ShipInfo.of(shipInfoReply);
        double dx = at.x - x, dy = at.y - y, dz = at.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= POSE_TOLERANCE;
    }

    /**
     * Wait for THIS craft's hull to be registered and then LOADED, and assert WHERE it stands; answer
     * its physics id.
     *
     * <p>Three steps, and the split is the point: a hull never registered, one registered and never
     * loaded, and one loaded in the wrong place are different defects, and this class exists to tell
     * crossings' defects apart.</p>
     *
     * <p>Both links are the substrate's own: {@code ship_spawned} is written from
     * {@code QueryableShipData.addShip} and carries the durable Stellurgy id the hull was bound with, so
     * the first wait names THIS craft; the physics id it also carries is what the second wait and the
     * pose read use.</p>
     */
    private String requireLoadedShipAt(long mark, int x, int y, String what) throws Exception {
        String spawned = events.awaitRecordWithField(mark, "ship_spawned", "stellurgyShip", durableShipId,
                what + " — no hull was ever registered for this craft", WAIT_TICKS);
        String vsShip = Events.text(spawned, "vsShip");
        events.awaitField(mark, "ship_loaded", "vsShip", vsShip,
                what + " — hull " + vsShip + " reached the registry and never became a loaded ship",
                WAIT_TICKS);
        String info = read("stellurgytest vs ship-info 0 id " + vsShip).raw();
        assertTrue(what + " — hull " + vsShip + " is loaded, but it does not stand within "
                        + POSE_TOLERANCE + " of " + x + "," + y + "," + BASE_Z + ": " + info,
                standsAt(info, x, y, BASE_Z));
        return vsShip;
    }

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

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
    private static final int NAMING_CYCLE_X = 9400, NAMING_CROSS_X = 9800, NAMING_DEPART_X = 10200;

    /**
     * A craft is built, dropped, and fetched back: ASSEMBLED, then UNLOADED, then LOADED — each once.
     */
    @RedWitness(verdict = "assembled once", member = "WorldServerShipManager#spawnNewShips",
            at = "noteNamed(toSpawn, spawnData.cause);",
            replacedBy = "noteNamed(toSpawn, ShipLifecycleEvent.Cause.ASSEMBLED); noteNamed(toSpawn, ShipLifecycleEvent.Cause.ASSEMBLED);",
            fails = "must be announced as ASSEMBLED exactly once", on = "2026-10-18")
    @RedWitness(verdict = "unloaded", member = "WorldServerShipManager#loadAndUnloadShips",
            at = "noteUnloaded(physicsObject.getShipData());",
            replacedBy = "",
            fails = "dropping the ship object must be announced", on = "2026-10-18")
    @RedWitness(verdict = "loaded once", member = "WorldServerShipManager#loadAndUnloadShips",
            at = "noteNamed(toLoad, ShipLifecycleEvent.Cause.LOADED);",
            replacedBy = "noteNamed(toLoad, ShipLifecycleEvent.Cause.LOADED); noteNamed(toLoad, ShipLifecycleEvent.Cause.LOADED);",
            fails = "coming back must be announced exactly once", on = "2026-10-18")
    @LegacyWitness(prose = "2026-09-30: the spawn note at {@code WorldServerShipManager#spawnNewShips} at"
            + " {@code noteNamed(toSpawn, spawnData.cause);} removed fails \"a craft that has just been built must be"
            + " announced\" (taken on the pre-2026-10-06 {@code noteLifecycle} form, not re-taken).")
    @RedWitness(verdict = "loaded", member = "WorldServerShipManager#loadAndUnloadShips",
            at = "noteNamed(toLoad, ShipLifecycleEvent.Cause.LOADED);",
            replacedBy = "",
            fails = "coming back must be announced", on = "2026-10-18")
    @RedWitness(verdict = "unloaded once", member = "WorldServerShipManager#loadAndUnloadShips",
            at = "noteUnloaded(physicsObject.getShipData());",
            replacedBy = "noteUnloaded(physicsObject.getShipData()); noteUnloaded(physicsObject.getShipData());",
            fails = "dropping the ship object must be announced exactly once", on = "2026-10-18")
    @RedWitness(verdict = "not pasted", member = "WorldServerShipManager#loadAndUnloadShips",
            at = "noteNamed(toLoad, ShipLifecycleEvent.Cause.LOADED);",
            replacedBy = "noteNamed(toLoad, ShipLifecycleEvent.Cause.LOADED); noteNamed(toLoad, ShipLifecycleEvent.Cause.PASTED);",
            fails = "nothing here was cut and pasted", on = "2026-10-18")
    @RedWitness(verdict = "not destroyed", member = "WorldServerShipManager#loadAndUnloadShips",
            at = "noteUnloaded(physicsObject.getShipData());",
            replacedBy = "noteUnloaded(physicsObject.getShipData()); noteRemoved(physicsObject.getShipData());",
            fails = "an unloaded craft still EXISTS", on = "2026-10-18")
    @Test
    public void buildingDroppingAndFetchingBackAreThreeDistinctAnnouncements() throws Exception {

        long mark = events.mark();
        String shipId = buildAnnouncedShipAt(NAMING_CYCLE_X);
        awaitAnnounced(mark, shipId, "ASSEMBLED", "a craft that has just been built must be announced");

        // Nothing holds a ship loaded on a headless server once permanent loading is off: there is no
        // player for the world's own pass to measure a distance to, so it drops the craft by itself.
        ShipReadiness.letShipsUnload(c -> arrange(c).raw(), "the un-naming half is the subject: the craft has to be"
                + " dropped by the world's own pass");
        String window;
        try {
            awaitAnnounced(mark, shipId, "UNLOADED", "dropping the ship object must be announced");
        } finally {
            ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "the LOADED edge is the last subject: the craft has"
                    + " to be fetched back — and this scenario's opt-out ends with it");
        }
        arrange("stellurgytest vs load-ship 0 " + shipId).requireOk("ask for the craft by its id");
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
     */
    @RedWitness(verdict = "pasted", member = "WorldServerShipManager#spawnNewShips",
            at = "noteNamed(toSpawn, spawnData.cause);",
            replacedBy = "noteNamed(toSpawn, ShipLifecycleEvent.Cause.ASSEMBLED);",
            fails = "a craft re-registered around pasted blocks must be announced as PASTED", on = "2026-10-18")
    @RedWitness(verdict = "pasted once", member = "WorldServerShipManager#spawnNewShips",
            at = "noteNamed(toSpawn, spawnData.cause);",
            replacedBy = "noteNamed(toSpawn, spawnData.cause); noteNamed(toSpawn, spawnData.cause);",
            fails = "a craft re-registered around pasted blocks must be announced as PASTED exactly", on = "2026-10-18")
    @RedWitness(verdict = "not a build", member = "WorldServerShipManager#spawnNewShips",
            at = "noteNamed(toSpawn, spawnData.cause);",
            replacedBy = "noteNamed(toSpawn, spawnData.cause); noteNamed(toSpawn, ShipLifecycleEvent.Cause.ASSEMBLED);",
            fails = "a crossing is not a new build", on = "2026-10-18")
    @Test
    public void aCrossingIsAnnouncedAsAPasteAndNotAsANewBuild() throws Exception {

        long mark = events.mark();
        String shipId = buildAnnouncedShipAt(NAMING_CROSS_X);
        ArrangementFailure.arranged(() -> awaitAnnounced(mark, shipId, "ASSEMBLED",
                "the craft must exist before it can cross"));

        // The production crossing: the blocks are cut out and pasted elsewhere, and the craft is
        // re-registered around them. Told WHICH craft to cut; the source pose is only where the
        // riders are gathered.
        ShipInfo source = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        String repack = act("stellurgytest vs ship-repack 0 id " + shipId + " "
                + (int) source.x + " " + (int) source.y + " " + (int) source.z + " "
                + (NAMING_CROSS_X + HOP) + " " + FixtureSite.OPEN_AIR_Y + " " + NAMING_Z).raw();
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
     * The SOURCE of a crossing is announced as a departure to where it went — never as destroyed.
     *
     * <p>Contract: this fails if production stops telling a departure from a destruction when a
     * crossing's source leaves this world's registry. The two removals are identical at the registry,
     * so the crossing declares the departure to the ship manager before it cuts, and the manager's
     * removal announces {@code DEPARTED} with the destination instead of {@code DESTROYED}. Told
     * "destroyed", every consumer holding something durable for the vessel throws it away at the
     * moment the vessel is being carried, crew aboard, to the next cell.</p>
     *
     * <p>Same-world on purpose: the crossing here pastes into the world it cut from, so the source's
     * registration is dropped by the spawn drain as a blockless remnant of the identity being
     * re-registered — the path on which the two announcements of one removal used to disagree. The
     * destination is therefore this world's own dimension.</p>
     *
     * <p>The window is read once the ARRIVAL is announced: the source's removal is published in that
     * pass or an earlier one, so by then a destruction of it would be on the record too.</p>
     */
    @RedWitness(member = "VSBridge#declareDeparture",
            at = "ValkyrienUtils.getServerShipManager(world).declareDeparture(uuid, destinationDim);",
            replacedBy = "",
            fails = "the source of a crossing must be announced as DEPARTED exactly once", on = "2026-10-18")
    @Test
    public void aCrossingsSourceIsAnnouncedAsDepartedAndNeverAsDestroyed() throws Exception {
        long mark = events.mark();
        String shipId = buildAnnouncedShipAt(NAMING_DEPART_X);
        ArrangementFailure.arranged(() -> awaitAnnounced(mark, shipId, "ASSEMBLED",
                "the craft must exist before it can cross"));

        ShipInfo source = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        long crossMark = events.mark();
        String repack = act("stellurgytest vs ship-repack 0 id " + shipId + " "
                + (int) source.x + " " + (int) source.y + " " + (int) source.z + " "
                + (NAMING_DEPART_X + HOP) + " " + FixtureSite.OPEN_AIR_Y + " " + NAMING_Z).raw();
        requireArranged("the crossing must actually cut and paste the craft, or nothing below is a"
                + " departure: " + repack, Reply.of(repack).ok());
        String arrivedAs = Reply.of("stellurgytest vs ship-repack", repack).text("shipUuid");
        ArrangementFailure.arranged(() -> awaitAnnounced(crossMark, arrivedAs, "PASTED",
                "the crossed craft must be re-registered at its destination before its source's"
                        + " announcements are counted"));

        String window = events.since(crossMark, "ship_lifecycle");
        Events.assertInstrumentRan(window, LIFECYCLE_INSTRUMENT,
                "the crossing's source was announced as departed and not as destroyed");
        java.util.List<String> departures =
                Events.recordsWhereAll(window, "ship", shipId, "cause", "DEPARTED");
        assertEquals("the source of a crossing must be announced as DEPARTED exactly once: " + window,
                1, departures.size());
        assertEquals("a departure must carry where the craft went — this crossing pastes into dim 0: "
                + departures.get(0), 0, Reply.of(departures.get(0)).integer("destinationDim"));
        assertEquals("the source of a crossing is alive in its destination; announced as DESTROYED, every"
                + " consumer holding something durable for this vessel would throw it away while the"
                + " vessel is being carried: " + window, 0, announced(window, shipId, "DESTROYED"));
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

    /** Build and assemble a craft on its own lane, and answer its physics identity once it is usable. */
    private String buildAnnouncedShipAt(int baseX) throws Exception {
        ShipBirth birth = buildAt(FixtureSite.openAir(0, baseX, NAMING_Z));
        return ArrangementFailure.arranged(() -> birth.awaitUsable(c -> read(c).raw(), events, WAIT_TICKS));
    }
}
