package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

/**
 * The shared-client base for the Valkyrien Skies / tier-2 ship scenarios.
 *
 * <p>It is a separate base class and not two more commands in {@link AbstractSharedClientE2ETest}
 * because that class's reset is paid by every scenario in the client tier, and the two channels
 * below belong to ship scenarios only.</p>
 *
 * <h2>The three channels a ship scenario leaves behind</h2>
 *
 * <ol>
 *   <li><b>The player is still RIDING.</b> Nearly every scenario here ends seated on a pilot seat's
 *       dummy or captured by a deck. A passenger is not moved by {@code /tp}, so without this the
 *       shared reset's plot assertion fails naming coordinates — the symptom, not the cause — and a
 *       scenario that opens by mounting would mount a seat it is already sitting on.</li>
 *   <li><b>{@code vs permaload}.</b> The headless affordance that keeps a freshly assembled ship
 *       loaded with no player to hold it. Several scenarios switch it on and never switch it off,
 *       which hands the next scenario a world where ships never unload — and a scenario whose
 *       subject IS the unload (a reload, a client-load gate) would then silently measure the
 *       affordance instead of the product. It is reset to OFF, so a scenario that needs it SETS
 *       it.</li>
 *   <li><b>The flight computer's probe command channels.</b> They are per-tile and name one ship
 *       each, so they cannot bleed onto a neighbour — but they deliberately OUTRANK the pilot
 *       channel, so one left in force hands the next scenario a computer that ignores its own pilot.
 *       They replaced four {@code static volatile} channels that every computer read as a fallback,
 *       where a probe throttle was not aimed at the ship it named at all: it kept flying every other
 *       ship in the world, including the next
 *       scenario's, until something cleared it. Under one boot per test there was never a next
 *       scenario, which is why this only surfaced here.</li>
 * </ol>
 *
 * <p>Both are closed and then ASSERTED, on the same principle as the base reset: a reset nobody
 * checks is indistinguishable from no reset.</p>
 */
public abstract class AbstractSharedVsClientE2ETest extends AbstractSharedClientE2ETest {

    /**
     * Where the parking plots live for a ship class.
     *
     * <p>Ship fixtures in this tier are built on the ground along the x==z diagonal between roughly
     * 2800 and 6500, and each scenario keeps the base coordinates its green runs were taken on. The
     * plots this lane hands out are only the place the reset PARKS the player between scenarios, so
     * they are pushed well off that diagonal: a plot that contained another scenario's ship would
     * make "stay inside your plot" mean nothing.</p>
     */
    protected static final Plot.Lane SHIP_PARKING_LANE = new Plot.Lane(2000, 8000, Plot.SIZE);

    /**
     * How far from its query point a {@code vs ship-info} answer may be and still be attributed to
     * THIS scenario, in blocks — <b>for the CAPTURE only</b>.
     *
     * <p>{@code vs ship-info <dim> <x> <y> <z>} is a NEAREST-ship lookup
     * ({@code VSBridge.nearestShip}) — it reports whichever loaded ship is closest to the point, and
     * until this constant existed it had no distance bound at all. With one ship in the world that
     * is exact. With several, it answers with a NEIGHBOUR the moment this scenario's ship unloads or
     * flies off, and the reply is indistinguishable from a correct one: the caller gets a plausible
     * position, attitude and angular velocity belonging to a ship it never built.</p>
     *
     * <p>48 is chosen against the tier's own geometry: these classes space their fixtures <b>100
     * blocks</b> apart, so a bound below 50 can never admit a neighbour.</p>
     *
     * <p><b>It is not, and cannot be, an identity.</b> The distance {@code nearestShip} compares is
     * the full 3-D one, Y included, and this tier's flight scenarios climb on purpose — one holds
     * the lift key for 60 uninterrupted ticks to clear the terrain, which at the flight model's cap
     * is over a hundred blocks. Bounded, every later query about that ship answers
     * {@code managed:false}; unbounded, it answers about the neighbour. Neither is a report about
     * the ship the scenario built.</p>
     *
     * <p>So the bound is spent <b>once</b>, by {@link #captureShipIdAt}, at the only moment it is
     * defensible — the scenario's own ship freshly assembled at its own base, before anything has
     * moved — and everything afterwards goes through {@link #shipInfoById}, which has no distance
     * term at all.</p>
     */
    protected static final int SHIP_QUERY_RADIUS = SHIP_CAPTURE_RADIUS_BLOCKS;

    private static final Pattern SHIP_ID = Pattern.compile("\"id\":\"([^\"]*)\"");
    /** The two quaternion components an upright test needs; see {@link #upYOf}. */
    private static final Pattern Q_X = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_Z = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");

    /**
     * Wait for this scenario's ship to LOAD at its own base and return its IDENTITY — the value
     * every later question about that ship is keyed on.
     *
     * <p>Fails as an ARRANGEMENT failure rather than a contract one: a scenario whose fixture never
     * became a loaded ship has not disproved anything about ships.</p>
     *
     * @param samples how many 5-tick polls to spend waiting for the load.
     */
    protected final String captureShipIdAt(int bx, int by, int bz, int samples) throws Exception {
        String info = "";
        String id = null;
        for (int attempt = 0; attempt < samples && id == null; attempt++) {
            bot().waitTicks(5);
            info = exec("artest vs ship-info 0 " + bx + " " + by + " " + bz
                    + " " + SHIP_QUERY_RADIUS);
            if (info.contains("\"managed\":true")) {
                id = readShipId(info);
            }
        }
        scenario().record("shipIdAt_" + bx + "_" + bz, id == null ? info : id);
        scenario().requireArranged("this scenario's ship must LOAD at its own base ("
                + bx + "," + by + "," + bz + ") within " + SHIP_QUERY_RADIUS + " blocks before"
                + " anything can be asked about it — last reply " + info, id != null);
        return id;
    }

    /** {@link #captureShipIdAt(int, int, int, int)} with this tier's usual load budget. */
    protected final String captureShipIdAt(int bx, int by, int bz) throws Exception {
        return captureShipIdAt(bx, by, bz, 40);
    }

    /**
     * The ship report for {@code shipId}, wherever that ship now is. A {@code managed:false} here
     * means that ship is not loaded — never "it is somewhere else", which is the whole point of
     * asking this way.
     */
    protected final String shipInfoById(String shipId) throws Exception {
        return exec("artest vs ship-info 0 id " + shipId);
    }

    /** How many two-tick polls a client is given to re-establish a rider's mount after a dimension
     *  change. 80 ticks: generous against an eight-fork load, short enough that a crossing which
     *  genuinely drops its rider fails here rather than waiting out a budget. */
    protected static final int CLIENT_REMOUNT_POLLS = 40;

    /**
     * The client's mount state once it has caught up with a dimension change — or an
     * {@link AssertionError} carrying THE CHAIN that explains why it never did.
     *
     * <p>A dimension change tears the client's world down and rebuilds it, and the mount to the seat
     * entity is re-established after the new dimension is known. A scenario that reads
     * {@code riding} in the same breath sees {@code false} — not because the crossing dropped
     * anyone, but because it asked a tick too soon.
     *
     * <p><b>The point is not the wait, it is what a failure SAYS.</b> "He is not riding" names a
     * symptom and leaves the reader to guess whether the test was early or the product broke. So on
     * timeout this reports the SERVER's own mount/dismount record across the same window: if it
     * seated him and the client did not follow, that is a replication lag; if it never seated him,
     * the crossing dropped him and no wait here would ever have helped. Raised through
     * {@code scenario().arrangementFailed}, so the failure is TYPED as an arrangement problem
     * rather than a contract one — the same cut the message describes, made machine-readable.
     * The mark is refused unless
     * the recorder says it is both live and woven — a dead recorder answers with a confident empty
     * list, which is the one answer that could mislead.
     */
    protected final JsonObject ridingOnceTheClientHasCaughtUp(int iterations) throws Exception {
        String mark = exec("artest events mark");
        boolean usable = mark.contains("\"recording\":true") && mark.contains("\"mixins\":true");
        java.util.regex.Matcher seqM =
                java.util.regex.Pattern.compile("\"seq\":(-?\\d+)").matcher(mark);
        long seq = seqM.find() ? Long.parseLong(seqM.group(1)) : -1L;

        JsonObject mount = bot().reportRidingEntity();
        for (int i = 0; i < iterations && !mount.get("riding").getAsBoolean(); i++) {
            bot().waitTicks(2);
            mount = bot().reportRidingEntity();
        }
        if (mount.get("riding").getAsBoolean()) {
            return mount;
        }
        String chain = usable && seq >= 0
                ? exec("artest events since " + seq + " mount") + " | "
                        + exec("artest events since " + seq + " dismount")
                : "NO CHAIN: the position-writer recorder was not usable at the mark (" + mark + ")";
        scenario().arrangementFailed("the client never reported the remount within "
                + (iterations * 2) + " ticks of the crossing. Client says " + mount
                + "; the SERVER's mount/dismount record across the same window says " + chain
                + " — if it seated him and the client did not follow this is a replication lag; if it"
                + " never seated him the crossing dropped him, and that is a PRODUCT defect, not a"
                + " wait that was too short.");
        return mount; // unreachable: arrangementFailed always throws
    }

    /**
     * How level a hull has to be before a pilot's "climb" is a climb at all.
     *
     * <p>A pilot's throttle is a BODY-frame command — it is mapped through the ship's attitude — so
     * on a hull lying over, "up" is mostly horizontal thrust and the craft travels instead of
     * rising. Any scenario whose claim is about ALTITUDE is therefore making a claim it cannot
     * support once the hull has tipped, and its red would name the control chain, the seat binding or
     * the crossing when none of them is at fault.</p>
     *
     * <p><b>The number is measured, not chosen.</b> A craft flying in clear air reads {@code up} =
     * 1.00 for the whole window; a craft that took its tilt from ground contact reads 0.59, 0.38 or
     * below within the first samples and decays toward 0. The audit that identified the substrate's
     * collision solver as the source of the torque stated its own acceptance in these terms — level
     * throughout at {@code >= 0.95}, tipped below {@code 0.9} — so this uses the same line.</p>
     */
    protected static final double UPRIGHT_UP_Y = 0.9;

    /** The world-frame Y of a ship's OWN up, from a {@code ship-info} reply, or NaN if unreported. */
    protected static double upYOf(String shipInfoJson) {
        Matcher qx = Q_X.matcher(shipInfoJson);
        Matcher qz = Q_Z.matcher(shipInfoJson);
        if (!qx.find() || !qz.find()) {
            return Double.NaN;
        }
        double ax = Double.parseDouble(qx.group(1));
        double az = Double.parseDouble(qz.group(1));
        return 1.0 - 2.0 * (ax * ax + az * az);
    }

    /**
     * Refuse to make an ALTITUDE claim about a hull that has tipped, and say so as a PRECONDITION
     * rather than as a verdict.
     *
     * <p>This is the difference between a red that reads "the restored control chain is dead" and one
     * that reads "the craft was lying on its side, so nothing here was ever a measurement of the
     * control chain". The state it declines to measure on is a known production defect with its own
     * ledger entry — a craft that takes off from a pad is tipped by the physics substrate's collision
     * response and the attitude law then holds the tilt, and a pilot cannot right it from the
     * controls — so a scenario blocked by it must not report its own subject as broken.</p>
     *
     * <p>An UNREPORTED attitude is not treated as tipped: a probe that answered nothing is a harness
     * problem, and turning it into a precondition failure would hide it.</p>
     *
     * @param shipInfoJson a {@code ship-info} reply for the craft the claim is about
     * @param theClaim     what the caller was about to assert, for the failure line
     */
    protected final void requireUprightForAnAltitudeClaim(String shipInfoJson, String theClaim)
            throws Exception {
        double up = upYOf(shipInfoJson);
        scenario().record("upY", up);
        if (Double.isNaN(up) || up >= UPRIGHT_UP_Y) {
            return;
        }
        scenario().step(Scenario.Phase.PRECONDITION, "read the hull's attitude before " + theClaim);
        scenario().arrangementFailed("the hull is LYING OVER (up=" + up + ", level is 1.0 and this"
                + " scenario needs at least " + UPRIGHT_UP_Y + "), so \"" + theClaim + "\" cannot be"
                + " measured here at all: the pilot's throttle is a body-frame command, and on a"
                + " tipped hull it is horizontal thrust. Nothing in this red is evidence about the"
                + " subject. The craft is tipped by the physics substrate's collision response when it"
                + " leaves the ground, and the attitude law then holds whatever tilt it was given."
                + " ship=" + shipInfoJson.replace('\n', ' '));
    }

    /**
     * Where a craft is lifted to before it is flown: high enough that the fixture's own launchpad is
     * far below it, low enough to stay under the lowest orbit line the config permits (255).
     */
    protected static final int CLEAR_AIR_Y = 150;

    /**
     * Take a freshly assembled craft OFF THE PAD it was built on, straight up, and prove it came up
     * level.
     *
     * <p><b>Why every scenario that flies wants this.</b> A craft assembled on a pad is resting on
     * solid blocks, and the physics substrate resolves that contact with an impulse applied at the
     * contact point — which on an asymmetric hull is off-axis and spins it. The attitude law then
     * pins its reference to wherever the pilot's craft now IS, so the tilt is permanent, and a
     * body-frame throttle on a hull lying over is horizontal thrust. Any scenario whose subject is
     * altitude therefore spends its whole window measuring a craft that cannot climb, and its red
     * accuses the control chain, the seat binding or the crossing instead. Off the ground the same
     * craft flies dead vertical.</p>
     *
     * <p>This is arrangement, not a workaround for a test: a player launches from a pad and gets the
     * same tilt, which is a live production defect with its own ledger entry. What the lift buys is
     * the ability to measure anything ELSE while that defect stands.</p>
     *
     * <p>The move is the substrate's own rigid teleport: the pose moves, the subspace blocks stay,
     * riders are carried. It leaves the ship PARKED by VS's recipe, so physics is re-enabled
     * afterwards and the arrival is read back BY IDENTITY — a positional read at the old base would
     * answer about whatever is nearest to a place this craft has just left.</p>
     *
     * @return the ship's report at its new altitude
     */
    protected final String liftClearOfTheGround(String shipId, int toY) throws Exception {
        // The substrate's load controller drops a RIGID-TELEPORTED ship's physics object even with a
        // pilot aboard, and a ship that is not loaded is not ticked: its flight computer stops, so it
        // stops climbing and stops being reported at all. Measured 2026-08-23 — a craft lifted to 147
        // flew to 242 at full commanded speed and then went silent for the remaining ten minutes of
        // its window, with the gate reporting afcResolved=false. The affordance that holds it is this
        // one, and the family reset switches it back off.
        String held = exec("artest vs permaload true");
        scenario().requireArranged("a lifted ship must be held loaded, or the substrate's load"
                + " controller drops it mid-climb and every later reading is about a ship that is no"
                + " longer being ticked: " + held, held.contains("\"ok\":true"));

        String before = shipInfoById(shipId);
        double x = readDoubleOr(before, POS_X, Double.NaN);
        double z = readDoubleOr(before, POS_Z, Double.NaN);
        scenario().requireArranged("the craft must report a position before it can be lifted off its"
                + " pad: " + before, !Double.isNaN(x) && !Double.isNaN(z));

        String moved = exec("artest vs teleport-ship-by-id 0 " + shipId
                + " " + x + " " + toY + " " + z);
        scenario().requireArranged("the lift off the pad must take, or the craft flies its whole"
                + " window in ground contact: " + moved, moved.contains("\"ok\":true"));
        bot().waitTicks(30); // transform adoption + rider sync settle
        String unparked = exec("artest vs unpark-by-id 0 " + shipId);
        scenario().requireArranged("the rigid teleport leaves the ship PARKED by the substrate's own"
                + " recipe, and a parked ship cannot be flown: " + unparked,
                unparked.contains("\"ok\":true"));
        bot().waitTicks(10);

        String after = shipInfoById(shipId);
        double y = readDoubleOr(after, POS_Y, Double.NaN);
        scenario().requireArranged("the lifted craft must still be loaded and report its new"
                + " altitude (asked BY IDENTITY, so this cannot be a neighbour): " + after,
                !Double.isNaN(y) && Math.abs(y - toY) < 20.0);
        // The whole point of the lift, ASSERTED rather than assumed: it is only worth doing if the
        // craft is level when it arrives, and a craft that was already tipped on the pad stays tipped
        // through a rigid move.
        requireUprightForAnAltitudeClaim(after, "flying the craft after lifting it off its pad");
        scenario().record("liftedTo", y);
        return after;
    }

    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static double readDoubleOr(String json, Pattern p, double fallback) {
        Matcher m = p.matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : fallback;
    }

    /** The {@code "id"} field of a {@code ship-info} reply, or null when it carries none. */
    protected static String readShipId(String shipInfoJson) {
        Matcher m = SHIP_ID.matcher(shipInfoJson);
        return m.find() && !m.group(1).isEmpty() ? m.group(1) : null;
    }

    @Override
    protected Plot.Lane lane() {
        return SHIP_PARKING_LANE;
    }

    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        exec("artest player dismount");
        exec("artest vs permaload false");
        // Release every per-tile PROBE command channel on the server. They name one ship each and
        // cannot bleed onto a neighbour, but they deliberately OUTRANK the pilot channel, so a
        // scenario that left one in force hands the next scenario a computer that ignores its own
        // pilot. The clear walks the loaded computers of every loaded world.
        //
        // This verb was born for a worse problem, now gone: a JVM-wide static flight input every
        // computer read as its fallback. Measured 2026-08-07 — a pilot-key scenario whose ship
        // climbed 32.7 blocks where the same body run alone climbs ~2, because an earlier scenario's
        // throttle was still held on a channel that belonged to nobody.
        //
        // Asserted, not trusted: a clear nobody checks is indistinguishable from no clear.
        String afcCleared = exec("artest vs afc-clear");
        assertTrue("the flight computer's bring-up channels must be cleared between"
                + " scenarios, or a later scenario's ship flies under an earlier one's throttle;"
                + " probe replied " + afcCleared, afcCleared.contains("\"ok\":true"));

        // Asserted on the CLIENT's own view, and polled: the dismount is a server write and the
        // client learns it on the next update packet, so reading once would pin the round-trip
        // rather than the state.
        JsonObject riding = bot().reportRidingEntity();
        for (int waited = 0; waited < 40 && isRiding(riding); waited += 5) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
        }
        assertFalse("a ship scenario must start un-seated as the CLIENT renders it, or its own"
                + " mount step measures the previous scenario's seat — and /tp does not move a"
                + " passenger, so the plot assertion that follows would fail for the wrong reason."
                + " client reports " + riding, isRiding(riding));
        scenario().record("resetRiding", riding);
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

}
