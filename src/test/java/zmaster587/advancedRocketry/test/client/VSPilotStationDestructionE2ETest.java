package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;

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

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SEAT_SUB = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern AFC_SUB = Pattern.compile(
            "\"afcX\":(-?\\d+),\"afcY\":(-?\\d+),\"afcZ\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";

    /** How long one link of the destruction may take. A deadline for discrete events, not a guess
     *  at how long a value settles: the whole release happens inside one {@code breakBlock} call. */
    private static final int RELEASE_BUDGET_TICKS = 200;

    /** The key the flight computer's own destruction notice is composed from — the message's
     *  identity, where the rendered English is one translation of it. */
    private static final String KEY_AFC_DESTROYED = "msg.pilotseat.afcdestroyed";

    @Test
    public void breakingTheOccupiedSeatDismountsThePilotAndHoldsTheShip() throws Exception {
        int bx = 4400, by = 64, bz = 4400;
        FlyingShip ship = assembleLoadAndFly(bx, by, bz);

        // Break the seat WHILE the climb key is still held — the exact latch scenario: the client
        // can no longer send a release (the seat tile is gone), so only the destruction handler
        // stands between the ship and flying the last command forever.
        Events events = events();
        long breakMark = events.markInstrumented();
        String broke = exec("artest fill 0 " + ship.seatX + " " + ship.seatY + " " + ship.seatZ
                + " " + ship.seatX + " " + ship.seatY + " " + ship.seatZ + " minecraft:air");
        assertTrue("breaking the seat block failed: " + broke, broke.contains("\"ok\":true"));
        try {
            // The release, as the two links breakBlock commits in ITS OWN source order: it resolves
            // the seat's linked computer and tells it the station is gone (which drops the pilot's
            // live input and zeroes the cruise setpoint), and only THEN throws the rider off. A red
            // here names which of the two did not happen — where the altitude window below reports
            // one number whether the computer was never told, was told and ignored it, or was told
            // and the substrate coasted.
            events.assertChain(breakMark, "destroying the OCCUPIED pilot seat must tell the linked"
                            + " flight computer its control station is gone and then throw the rider"
                            + " off", RELEASE_BUDGET_TICKS,
                    "control_station_lost", "dismount");

            // The rider must be DISMOUNTED as the CLIENT renders it. The server dismount is already
            // on the chain above, so an expiry here is a replication statement and not an open
            // question about whether the seat released him.
            JsonObject riding = awaitRiding(40, false);
            assertTrue("destroying the OCCUPIED pilot seat must dismount the rider (client-observed): "
                            + riding, !isRiding(riding));

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

        // The seat's mount dummy must be gone, as the CLIENT sees the world.
        int dummies = clientDummyCount();
        assertTrue("the destroyed seat's mount dummy must be removed (client sees " + dummies + ")",
                dummies == 0);
    }

    @Test
    public void breakingTheLinkedComputerDismountsMessagesAndNeverThrusts() throws Exception {
        int bx = 4600, by = 64, bz = 4600;
        FlyingShip ship = assembleLoadAndFly(bx, by, bz);
        bot().releaseKey(Keyboard.KEY_R);
        bot().waitTicks(10);

        Events events = events();
        long breakMark = events.markInstrumented();
        long breakClientMark = clientEvents().mark();
        String broke = exec("artest fill 0 " + ship.afcX + " " + ship.afcY + " " + ship.afcZ
                + " " + ship.afcX + " " + ship.afcY + " " + ship.afcZ + " minecraft:air");
        assertTrue("breaking the flight computer block failed: " + broke,
                broke.contains("\"ok\":true"));

        // The two links the computer's breakBlock commits per seated rider, in its own source
        // order: it TELLS him first and then throws him off. Nothing else in this window sends a
        // status message, so the absence of the first is the diagnosis a bare overlay poll could
        // never make — "no dummy resolved to this computer" reads exactly like "sent and the client
        // never showed it".
        events.assertChain(breakMark, "destroying the linked flight computer must tell the pilot"
                        + " his computer is gone and then dismount him", RELEASE_BUDGET_TICKS,
                "status_message_sent", "dismount");
        String sent = events.since(breakMark, "status_message_sent");
        assertTrue("the notice the computer's destruction sends must be keyed on "
                + KEY_AFC_DESTROYED + ": " + sent,
                sent.contains("\"key\":\"" + KEY_AFC_DESTROYED + "\""));

        // The pilot is dismounted, as the CLIENT renders it (the server dismount is on the chain).
        JsonObject riding = awaitRiding(40, false);
        assertTrue("destroying the linked flight computer must dismount the pilot (client-observed): "
                        + riding, !isRiding(riding));
        // ...and the line the server sent must actually have reached his HUD. Read off the client's
        // own record rather than the action-bar overlay, which the client counts down and discards:
        // the poll it replaces raced vanilla's own dismount hint for the same strip of screen.
        String shown = awaitClientChat(breakClientMark, "destroyed", RELEASE_BUDGET_TICKS,
                "the dismounted pilot must be told his flight computer was destroyed");
        assertTrue("the line the client was handed must say the computer was destroyed: " + shown,
                shown.toLowerCase(Locale.ROOT).contains("destroyed"));

        // A brainless ship must never keep thrusting upward: the dead computer's channels die
        // with the tile. (It is free to FALL — only continued powered climb is the defect.)
        double y1 = shipY(ship.id);
        bot().waitTicks(80);
        double y2 = shipY(ship.id);
        assertTrue("a ship whose flight computer was destroyed must not keep climbing under the "
                        + "dead computer's last command (y1=" + y1 + " y2=" + y2 + ")",
                y2 <= y1 + 2.0);

        int dummies = clientDummyCount();
        assertTrue("the seat's mount dummy must be removed when the computer is destroyed "
                        + "(client sees " + dummies + ")", dummies == 0);
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
    }

    /**
     * Assemble the with-pilot-seat ship at the base, load it with the client nearby, resolve the
     * seat's and computer's SUBSPACE blocks (stationary — resolved before the climb), seat the bot
     * (probe mount — the harness cannot right-click a subspace block), and fly it up a couple of
     * blocks on a REAL held key. Returns with the climb key still held.
     */
    private FlyingShip assembleLoadAndFly(int bx, int by, int bz) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        // The registry's own record of the ship being added, since a mark taken before the assembly
        // was queued: THIS scenario's ship by construction, where the count it replaces is
        // incremented by every neighbour that ever assembled one on this shared world.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        FlyingShip ship = new FlyingShip();
        ship.id = awaitShipSpawned(events, spawnMark,
                "assembly must create a VS ship in the queryable registry (async spawn)");
        bot().waitTicks(40); // settle before the observer approaches; the LOAD is awaited below

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);
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
        String found = exec("artest vs find-seat 0 id " + ship.id);
        Matcher sm = SEAT_SUB.matcher(found);
        assertTrue("find-seat must resolve the ship's subspace seat: " + found, sm.find());
        ship.seatX = Integer.parseInt(sm.group(1));
        ship.seatY = Integer.parseInt(sm.group(2));
        ship.seatZ = Integer.parseInt(sm.group(3));
        Matcher am = AFC_SUB.matcher(found);
        assertTrue("find-seat must resolve the seat's linked computer: " + found, am.find());
        ship.afcX = Integer.parseInt(am.group(1));
        ship.afcY = Integer.parseInt(am.group(2));
        ship.afcZ = Integer.parseInt(am.group(3));

        // Seat the bot and fly up on the REAL key path until the climb is unambiguous. The seat is
        // addressed by the subspace block find-seat just resolved FOR THIS SHIP: `vs seat-mount`
        // takes the first pilot seat in the world with no position filter, which mounts a
        // neighbour's ship once several scenarios share a world.
        String mountInfo = exec("artest vs seat-mount-at 0 " + ship.seatX + " " + ship.seatY
                + " " + ship.seatZ);
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount-at must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount, mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        final double baseY = y0;
        bot().holdKey(Keyboard.KEY_R);
        // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 100-iteration budget
        // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
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

    /** Poll until the client reports riding == {@code want} (bounded); returns the last sample.
     *  A CLIENT-rendered dismount has no record of its own — the position writers are server-side —
     *  so this stays a poll, and each call site states the server link it is following. */
    private JsonObject awaitRiding(int samples, boolean want) throws Exception {
        JsonObject riding = null;
        for (int i = 0; i < samples; i++) {
            riding = bot().reportRidingEntity();
            if (isRiding(riding) == want) {
                break;
            }
            bot().waitTicks(5);
        }
        return riding;
    }

    /**
     * Wait for the client's HUD to be HANDED a line containing {@code needle}, and return its text.
     *
     * <p>The client half of a message, off {@code client_chat_received} — the harness records every
     * line the in-game HUD is given, chat and action bar alike, so a record made seconds ago is
     * still there when this asks. The overlay poll it replaces read a FADING value that vanilla's
     * own "press X to dismount" hint writes over on exactly this path, so its silence meant nothing.</p>
     *
     * <p>Written here rather than on the shared base because this class does not own that base;
     * three other classes in this family carry the same lines for the same reason.</p>
     */
    private int clientDummyCount() throws Exception {
        return bot().reportEntities("EntityDummy", 64.0).getAsJsonArray("entities").size();
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

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
