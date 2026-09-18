package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Destroying a piloted ship's control station RELEASES the ship instead of latching it. Two
 * destruction targets, each its own contract:
 *
 * <ul>
 *   <li><b>The occupied pilot seat</b>: the rider is dismounted, the seat's mount dummy is
 *       removed, and the flight computer drops the pilot's last input AND his cruise setpoint —
 *       the ship reverts to an unmanned station-hold. Without that, the computer executes the last
 *       command every tick and (with Flight Assist ramping the cruise) the ship becomes an
 *       uncontrollable runaway accelerating away with nobody at the controls.</li>
 *   <li><b>The linked flight computer</b>: the pilot is dismounted, told the computer is gone
 *       (action bar), the dummy removed — and the dead computer's command channels die with it, so
 *       nothing keeps thrusting a brainless ship.</li>
 * </ul>
 *
 * <p>Full honest path: a real client pilot (real held key → packet → seat → computer → force)
 * flies the ship; the break is a server-side block removal exactly like a mined block (the same
 * {@code breakBlock} path); the RELEASE itself is read as the chain production commits inside
 * {@code breakBlock} — the computer being told, the rider being thrown off, the pilot being told —
 * and its consequences are read from the CLIENT (riding state, entity presence) with the server
 * ship position as the motion oracle.</p>
 *
 * <p><b>Why the release is a chain and not a wait.</b> A destroyed station produces three things
 * that a poll cannot separate: a computer that was never told (the seat resolved no computer), one
 * that was told and ignored it, and one that was told and whose substrate coasted. Only the first
 * is a seat bug, and only the chain can say which happened — where the altitude window that follows
 * reports one number for all three.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSPilotStationDestructionE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-pilot-station";
    }

    private static final String BUILDER_POS = "builderPos";
    private static final String POS_Y = "posY";
    private static final String DUMMY_ID = "dummyId";

    private static final String VARIANT = "with-pilot-seat";

    /** How long one link of the destruction may take. A deadline for discrete events, not a guess
     *  at how long a value settles: the whole release happens inside one {@code breakBlock} call. */
    private static final int RELEASE_BUDGET_TICKS = 200;

    // `KEY_AFC_DESTROYED` lived here. Nothing asks for the destruction NOTICE any more: what it
    // announces is the dismount, the dead thrust and the removed dummy, and those are asserted off
    // the game rather than off a sentence.

    @Test
    public void breakingTheOccupiedSeatDismountsThePilotAndHoldsTheShip() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;
        FlyingShip ship = assembleLoadAndFly(site);

        // Break the seat WHILE the climb key is still held — the exact latch scenario: the client
        // can no longer send a release (the seat tile is gone), so only the destruction handler
        // stands between the ship and flying the last command forever.
        Events events = events();
        long breakMark = events.markInstrumented();
        long breakClientMark = clientEvents().mark();
        String broke = exec("artest fill 0 " + ship.seatX + " " + ship.seatY + " " + ship.seatZ
                + " " + ship.seatX + " " + ship.seatY + " " + ship.seatZ + " minecraft:air");
        assertTrue("breaking the seat block failed: " + broke, Reply.of(broke).ok());
        try {
            // The release, as the three links breakBlock commits in ITS OWN source order: it
            // resolves the seat's linked computer and tells it the station is gone (which drops the
            // pilot's live input and zeroes the cruise setpoint), throws the rider off, and kills
            // the mount he was sitting on. A red here names which of the three did not happen —
            // where the altitude window below reports one number whether the computer was never
            // told, was told and ignored it, or was told and the substrate coasted.
            events.assertChain(breakMark, "destroying the OCCUPIED pilot seat must tell the linked"
                            + " flight computer its control station is gone, throw the rider off,"
                            + " and remove the mount he was on", RELEASE_BUDGET_TICKS,
                    "control_station_lost", "dismount", "entity_removed");
            assertRemovedThisScenariosDummy(events, breakMark, ship.dummyId, "server");

            // The rider must be DISMOUNTED as the CLIENT performs it — his own
            // `dismountRidingEntity`, off the mark taken before the break. The server dismount is
            // already on the chain above, so an expiry here is a replication statement and not an
            // open question about whether the seat released him.
            JsonObject riding = awaitClientDismount(breakClientMark,
                    "destroying the OCCUPIED pilot seat must dismount the rider on his own client",
                    RELEASE_BUDGET_TICKS);
            assertTrue("...and he must still be off the seat when it is read: " + riding,
                    !isRiding(riding));

            // The ship must revert to an unmanned HOLD — not keep flying the latched climb, and
            // not keep cruising a retained setpoint (destruction zeroes it). This IS a measurement:
            // an altitude staying put over a window is a physical value, not a link, and the link
            // that has to precede it is already asserted above. Let the brake settle, then require
            // the altitude to be stable over a 3-second window — with the key STILL physically
            // held, so a surviving latch would be climbing at cruise speed here.
            bot().waitTicks(40);
            double y1 = shipY(ship.id);
            bot().waitTicks(60);
            double y2 = shipY(ship.id);
            assertTrue("after the seat is destroyed the ship must HOLD, never fly the dead pilot's "
                            + "last command (y1=" + y1 + " y2=" + y2 + ")",
                    Math.abs(y2 - y1) < 2.0);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        // ...and the CLIENT must lose it too, off its own world's removal — the replication half.
        awaitDummyRemovedOnClient(breakClientMark, ship.dummyId);
    }

    @Test
    public void breakingTheLinkedComputerDismountsThePilotAndNeverThrusts() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;
        FlyingShip ship = assembleLoadAndFly(site);
        bot().releaseKey(Keyboard.KEY_R);
        bot().waitTicks(10);

        Events events = events();
        long breakMark = events.markInstrumented();
        long breakClientMark = clientEvents().mark();
        String broke = exec("artest fill 0 " + ship.afcX + " " + ship.afcY + " " + ship.afcZ
                + " " + ship.afcX + " " + ship.afcY + " " + ship.afcZ + " minecraft:air");
        assertTrue("breaking the flight computer block failed: " + broke,
                Reply.of(broke).ok());

        // What the computer's breakBlock commits per seated rider, in its own source order: it
        // throws him off, then kills the mount he was on. A `status_message_sent` link stood ahead
        // of both, and a chat check for the rendered word "destroyed" after them; both were about
        // the NOTICE, and both are gone.
        //
        // This was ONE link for a while, because with the message struck out only the dismount was
        // recorded — nothing published an entity's REMOVAL, so the pair had no second half. The
        // removal is now an event of its own and the order is assertable again.
        events.assertChain(breakMark, "destroying the linked flight computer must throw its pilot"
                        + " out of the seat and then remove the mount he was on",
                RELEASE_BUDGET_TICKS, "dismount", "entity_removed");
        assertRemovedThisScenariosDummy(events, breakMark, ship.dummyId, "server");

        // The pilot is dismounted as the CLIENT performs it (the server dismount is the link above).
        JsonObject riding = awaitClientDismount(breakClientMark,
                "destroying the linked flight computer must dismount the pilot on his own client",
                RELEASE_BUDGET_TICKS);
        assertTrue("...and he must still be off the seat when it is read: " + riding,
                !isRiding(riding));

        // A brainless ship must never keep thrusting upward: the dead computer's channels die
        // with the tile. (It is free to FALL — only continued powered climb is the defect.)
        double y1 = shipY(ship.id);
        bot().waitTicks(80);
        double y2 = shipY(ship.id);
        assertTrue("a ship whose flight computer was destroyed must not keep climbing under the "
                        + "dead computer's last command (y1=" + y1 + " y2=" + y2 + ")",
                y2 <= y1 + 2.0);

        awaitDummyRemovedOnClient(breakClientMark, ship.dummyId);
    }

    // ---- Shared arrangement --------------------------------------------------------------------

    private static final class FlyingShip {
        /**
         * The ship's IDENTITY, taken from the assembly's own {@code ship_spawned} record — this
         * scenario BUILT this ship, so it was told which one it is and never re-derives that from a
         * position. Every altitude read afterwards is keyed on it: the scenario's whole point is a
         * ship that CLIMBS, and a bounded nearest-ship query about the base it left answers
         * {@code managed:false} the moment the climb clears the bound, while an unbounded one
         * answers about a neighbour's craft.
         */
        String id;
        int seatX, seatY, seatZ;
        int afcX, afcY, afcZ;
        /**
         * The mount dummy this scenario seated the bot on, by entity id — the SUBJECT of every
         * removal assertion below. Entity ids are the server's and are replicated, so the same
         * number names the client's copy, which is what lets one identity be asked of both logs.
         */
        int dummyId;
    }

    /**
     * Assemble the with-pilot-seat ship at the base, load it with the client nearby, resolve the
     * seat's and computer's SUBSPACE blocks (stationary — resolved before the climb), seat the bot
     * (probe mount — the harness cannot right-click a subspace block), and fly it up a couple of
     * blocks on a REAL held key. Returns with the climb key still held.
     */
    private FlyingShip assembleLoadAndFly(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The registry's own record of the ship being added, since a mark taken before the assembly
        // was queued: THIS scenario's ship by construction, where the count it replaces is
        // incremented by every neighbour that ever assembled one on this shared world.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(site);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integerOr("rocketCount", Integer.MIN_VALUE) == 0));
        FlyingShip ship = new FlyingShip();
        ship.id = awaitShipSpawned(events, spawnMark,
                "assembly must create a VS ship in the queryable registry (async spawn)");
        bot().waitTicks(40); // settle before the observer approaches; the LOAD is awaited below

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what loads the ship, so the readiness link below is"
                        + " waiting on something only an arrived client can cause");
        // Identity is already settled above, off the record of THIS scenario's own assembly. What
        // still has to be waited for is a different fact the registry record does not prove: the
        // physics object being USABLE — production's own load event, the conjunction the physics
        // loop selects by, rather than a probe reading that answered a literal true. Keyed BY ID, so
        // it can neither drift onto a neighbour's craft nor lose sight of this one — and it fails as
        // an arrangement problem, which a fixture that never became a drivable ship is. The mark is
        // the pre-assembly one taken above: the event fires ONCE per load, so a later mark misses it.
        awaitShipUsable(events, spawnMark, ship.id);
        double y0 = shipY(ship.id);

        // Resolve the seat + computer SUBSPACE blocks now, while the ship still sits at its build
        // site (subspace addresses are stationary; the ship's world pose is about to change).
        PilotSeat seat = PilotSeat.byId(this::exec, 0, ship.id)
                .requireFound("find-seat must resolve the ship's subspace seat");
        ship.seatX = seat.seatX;
        ship.seatY = seat.seatY;
        ship.seatZ = seat.seatZ;
        assertTrue("find-seat must resolve the seat's linked computer: " + seat.raw(), seat.hasAfc);
        ship.afcX = seat.afcX;
        ship.afcY = seat.afcY;
        ship.afcZ = seat.afcZ;

        // Seat the bot and fly up on the REAL key path until the climb is unambiguous. The seat is
        // addressed by the subspace block find-seat just resolved FOR THIS SHIP: `vs seat-mount`
        // takes the first pilot seat in the world with no position filter, which mounts a
        // neighbour's ship once several scenarios share a world.
        String mountInfo = exec("artest vs seat-mount-at 0 " + ship.seatX + " " + ship.seatY
                + " " + ship.seatZ);
        ship.dummyId = Reply.of("artest vs seat-mount-at", mountInfo).integer(DUMMY_ID);
        long seatMark = clientEvents().mark();
        String mount = exec("artest player mount-entity " + ship.dummyId);
        assertTrue("bot must mount the seat dummy: " + mount, Reply.of(mount).bool("mounted", false));
        // The lift below is commanded by a real key held on a client that must already be riding;
        // ten ticks were a bet on that, and this whole class is about what happens to a pilot.
        awaitClientMount(seatMark, "the client must be riding the seat before its pilot flies it",
                RELEASE_BUDGET_TICKS, " | server said: " + mount);

        final double baseY = y0;
        bot().holdKey(Keyboard.KEY_R);
        // Event-gated hover-lift (bounded ceiling + early exit): the loop returns the moment the
        // ship has climbed, so the ceiling is patience and not how far it flies.
        // NOTE: this leg returns with KEY_R still HELD — the caller releases it, so no finally here.
        ClientPoll.Result<Double> lift = ClientPoll.until(bot()::waitTicks,
                () -> shipY(ship.id),
                y -> y - baseY > 2.0, 2, 100);
        double yAfter = lift.value;
        scenario().requireArranged("the seated bot must be flying the ship before its station can be "
                        + "destroyed (y0=" + y0 + " yAfter=" + yAfter + ")",
                yAfter - y0 > 2.0);
        return ship;
    }

    // ---- Observation helpers -------------------------------------------------------------------

    // `awaitRiding(samples, want)` lived here: a bounded poll of `reportRidingEntity`, justified in
    // its own javadoc by "a CLIENT-rendered dismount has no record of its own — the position writers
    // are server-side". That was WRONG about this tree. `MixinEntityPositionWriters` is in the
    // common mixin list, so it applies on both sides, and the client's own `dismountRidingEntity` —
    // which vanilla calls from `handleSetPassengers` when the server drops a passenger — records
    // `dismount` in the CLIENT log. The base's `awaitClientDismount` waits for that record.

    /**
     * The removal on the chain above was THIS scenario's mount, by entity id.
     *
     * <p><b>Why this stands beside {@code assertChain} rather than inside it.</b> A chain assertion
     * matches on the record TYPE, and on a world several scenarios share, any entity leaving any
     * loaded chunk produces an {@code entity_removed} — so the ORDER claim ("a removal came after
     * the dismount") is satisfiable by a neighbour's mob despawning. The chain buys the order; this
     * buys the subject, and neither buys the other. The two together are what the old bounded "how
     * many dummies are within 64 blocks" read could not say at all: it saw a count at one instant
     * and could not tell a removal that happened from one that happened and was undone.</p>
     *
     * @param log which log is being read, for the failure message — a mark belongs to ONE of them
     */
    private static void assertRemovedThisScenariosDummy(Events events, long mark, int dummyId,
                                                        String log) throws Exception {
        String removals = events.since(mark, "entity_removed");
        // Printed on a GREEN run, not only inside the failure: this record and its reader are both
        // new, and a defect in the READER cannot be found in a channel that opens only when the
        // SUBJECT breaks. It is also what tells the next reader what normal looks like here.
        // The DROP COUNT is printed with it, and that is not decoration: this type's ring turned
        // over 2605 times per leg on the run that introduced it, with every assertion passing. A
        // reader who only ever sees the records cannot tell a quiet log from a truncated one.
        System.out.println("[vs-pilot-station] " + log + " removals for e=" + dummyId + ": "
                + Events.records(removals)
                + " (evicted: " + Events.droppedOf(removals, "entity_removed") + ")");
        // The composition of what fills that ring was measured once, with
        // `fieldLines(since(0, "entity_removed"), "cls")` here, when the ring held 256 per type —
        // the numbers below are that measurement and are left as taken: 255 of 256 records were
        // `EntityFallingBlock` (every scenario bulk-fills its build site and the sand dies on
        // landing), which is why that class is now skipped by the recorder. What remains is passive
        // mobs leaving loaded chunks — 67 evictions across both legs of this class, against a
        // window of a few ticks between each mark and its read. The measurement is not left in: it
        // prints 256 lines, and the eviction count above is what a reader needs.
        assertTrue("the mount dummy this scenario seated the bot on (e=" + dummyId + ") must be the"
                        + " entity that was REMOVED on the " + log + " — a removal of something"
                        + " else satisfies the chain's type and says nothing about this seat: "
                        + removals,
                Events.countRecords(removals, "e", String.valueOf(dummyId)) > 0);
    }

    /**
     * The CLIENT's own world lost the dummy — the replication half, and an unmissable one.
     *
     * <p>The client removes it a tick or so after the server does (the destroy-entity packet marks
     * it dead; {@code WorldClient.tickEntities} then sweeps it), so this is a wait rather than a
     * read. It replaces {@code reportEntities("EntityDummy", 64)} at the end of each leg: a count of
     * what is nearby NOW is a sample, it is answered by any dummy in range including a neighbour's,
     * and its zero is equally produced by a client that never had the entity at all.</p>
     */
    private void awaitDummyRemovedOnClient(long clientMark, int dummyId) throws Exception {
        String seen = clientEvents().awaitField(clientMark, "entity_removed","e", dummyId,
                "the destroyed station's mount dummy (e=" + dummyId + ") must be removed from the"
                        + " CLIENT's world too — the server's removal is on the chain above, so an"
                        + " expiry here is a replication statement and not an open question about"
                        + " whether the seat let go of him", RELEASE_BUDGET_TICKS);
        System.out.println("[vs-pilot-station] client removal for e=" + dummyId + ": "
                + Events.records(seen)
                + " (evicted: " + Events.droppedOf(seen, "entity_removed") + ")");
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    /**
     * This ship's altitude, asked BY IDENTITY — the only form that survives what this class does.
     *
     * <p>Every reading here is taken of a ship that is CLIMBING, and it is read against the base it
     * launched from: a bounded nearest-ship query at that base answers {@code managed:false} as soon
     * as the climb clears the bound, and an unbounded one answers about whichever neighbour's craft
     * is now closest. Both are altitudes, neither is this ship's, and both look exactly like a real
     * reply — which on a shared world is the whole hazard.</p>
     */
    private double shipY(String shipId) throws Exception {
        return readDouble(shipInfoById(shipId), POS_Y);
    }

    private double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        assertTrue("expected a number `" + field + "` in: " + json, !Double.isNaN(value));
        return value;
    }

    private String assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume the craft is built in and FLOWN out of is EMPTY, measured by the
        // air fill's own `placed`. Open air, so this ASSERTS rather than digs — a craft climbing on
        // a held key out of a ten-block pit meets its rim, and the red then accuses the destruction
        // handler this class exists to test.
        site.requireClear(this::exec, 2, 16,
                "the hull, and the first blocks of the lane it climbs before the seat is broken");
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        return exec("artest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
    }
}
