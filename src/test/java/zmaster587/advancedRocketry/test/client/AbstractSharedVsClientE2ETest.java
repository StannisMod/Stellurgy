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
