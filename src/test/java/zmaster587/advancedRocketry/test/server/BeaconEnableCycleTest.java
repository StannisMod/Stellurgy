package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Beacon multiblock enable cycle on an AR-native
 * planet.
 *
 * <p>Production contract ({@code TileBeacon.setMachineEnabled(boolean)}):</p>
 *
 * <pre>{@code
 * if (DimensionManager.getInstance().isDimensionCreated(dim)) {
 *     DimensionProperties props = ...getDimensionProperties(dim);
 *     if (enabled)  props.addBeaconLocation(world, pos);
 *     else          props.removeBeaconLocation(world, pos);
 * }
 * }</pre>
 *
 * <p>Plus the block-break path ({@code BlockBeacon.breakBlock}):</p>
 *
 * <pre>{@code
 * if (tile instanceof TileBeacon && isDimensionCreated(dim))
 *     props.removeBeaconLocation(world, pos);
 * }</pre>
 *
 * <p>Pinning the registry mutation makes the "beacon-finder item locates
 * powered beacons" feature provable: the contract between {@code TileBeacon}
 * and {@code DimensionProperties.beaconLocations} is what the finder
 * item reads. Without these pins, either link can silently regress
 * (enable doesn't add, disable doesn't remove, break leaves an orphan
 * entry) and the finder item starts misbehaving with no compile-time
 * signal.</p>
 *
 * <p><b>Why AR-native planet only</b>: the {@code isDimensionCreated}
 * guard skips the registry call on overworld + any non-AR dim. Tests on
 * overworld would pass trivially (no mutation at all) — the contract
 * being verified is the WHOLE chain incl. the guard, so the test must
 * run on a dim the guard accepts.</p>
 *
 * <p><b>State sharing</b>: one AR planet generated in {@code @BeforeClass}
 * for all three methods — beacon locations don't leak between methods
 * because each test uses distinct controller coords and queries its
 * own pos in the dim's beacon set.</p>
 */
public class BeaconEnableCycleTest extends AbstractSharedServerTest {

    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 100;
    private static final int CX_ENABLE  = 100;
    private static final int CX_DISABLE = 200;
    private static final int CX_BREAK   = 300;

    /** The dim's beacon registry, as {@code "locations":[[x,y,z], …]}. */
    private static final String LOCATIONS = "locations";

    private static int planetDim = -1;

    @BeforeClass
    public static void generateSharedPlanet() throws Exception {
        Set<Integer> before = arDims();
        exec("ar planet generate 0 BeaconPhase3");
        Set<Integer> diff = arDims();
        diff.removeAll(before);
        assertTrue("planet generate must add exactly one dim — diff=" + diff,
                diff.size() == 1);
        planetDim = diff.iterator().next();

        String load = exec("artest dim load " + planetDim);
        assertTrue("planet dim load failed: " + load,
                Reply.of(load).bool("loaded"));
    }

    @AfterClass
    public static void deleteSharedPlanet() throws Exception {
        if (planetDim != -1) {
            try { exec("ar planet delete " + planetDim); } catch (Exception ignored) {}
            planetDim = -1;
        }
    }

    /** Powered + enabled beacon on an AR-created dim &rarr; controller pos
     *  appears in {@code DimensionProperties.beaconLocations}. */
    @Test
    public void enabledBeaconRegistersLocation() throws Exception {
        buildFixture(CX_ENABLE);
        enableMachine(CX_ENABLE, true);

        assertTrue("beacon list does not contain enabled controller pos"
                        + " (" + CX_ENABLE + "," + CY + "," + CZ + ") — "
                        + readBeaconList(),
                beaconListContains(CX_ENABLE, CY, CZ));
    }

    /** Counter-test: a beacon that's never enabled stays absent from
     *  the dim's beacon registry. */
    @Test
    public void disabledBeaconDoesNotRegister() throws Exception {
        buildFixture(CX_DISABLE);
        // Explicit set-enabled false (idempotent with default) so a stale
        // value from sibling test methods can't masquerade as "default
        // false" — even though setMachineEnabled(false) when already
        // false is a no-op, this guards against test ordering issues.
        enableMachine(CX_DISABLE, false);

        assertFalse("never-enabled beacon ended up in registry anyway"
                        + " (" + CX_DISABLE + "," + CY + "," + CZ + ") — "
                        + readBeaconList(),
                beaconListContains(CX_DISABLE, CY, CZ));
    }

    /** After enabling + verifying registration, breaking the controller
     *  block must unregister via the
     *  {@code BlockBeacon.breakBlock -> removeBeaconLocation} path. */
    @Test
    public void breakingControllerBlockUnregisters() throws Exception {
        buildFixture(CX_BREAK);
        enableMachine(CX_BREAK, true);
        assertTrue("baseline: enabled beacon must be registered first — "
                        + readBeaconList(),
                beaconListContains(CX_BREAK, CY, CZ));

        // MARKED BEFORE THE BREAK, so a red can say what the registry actually DID rather than only
        // what it ended up holding. This assertion has failed intermittently in the parallel tier
        // while passing serially, and its message could not distinguish three different defects: the
        // unregister never ran, it ran and something registered the position again, or the break
        // never reached production at all. The mixins behind `beacon_break` / `beacon_registered` /
        // `beacon_unregistered` separate them. An earlier attempt put that report in a production LOG
        // and it was unreadable from here — the mod logger writes into the server child's own log,
        // which nothing in this harness captures.
        String mark = exec("artest events mark");

        // Break the controller via place-air. world.setBlockState calls
        // the old block's breakBlock callback in Forge 1.12, which is
        // how BlockBeacon.breakBlock gets a chance to clean up the
        // registry entry.
        String breakResp = exec("artest place " + planetDim + " "
                + CX_BREAK + " " + CY + " " + CZ + " minecraft:air");
        assertTrue("could not air-replace controller block: " + breakResp,
                Reply.of(breakResp).ok());

        boolean stillThere = beaconListContains(CX_BREAK, CY, CZ);
        assertFalse("broken-controller beacon still in registry"
                        + " (" + CX_BREAK + "," + CY + "," + CZ + ") — "
                        + readBeaconList()
                        + " | what the registry did: " + beaconEventsSince(mark),
                stillThere);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static void buildFixture(int cx) throws Exception {
        String fixture = exec("artest fixture multiblock beacon "
                + planetDim + " " + cx + " " + CY + " " + CZ);
        assertTrue("beacon fixture build failed: " + fixture,
                Reply.of(fixture).ok());
        String tryComplete = exec("artest machine try-complete "
                + planetDim + " " + cx + " " + CY + " " + CZ);
        assertTrue("beacon structure failed to complete: " + tryComplete,
                Reply.of(tryComplete).bool("isComplete"));
    }

    private static void enableMachine(int cx, boolean enabled) throws Exception {
        String resp = exec("artest machine set-enabled " + planetDim + " "
                + cx + " " + CY + " " + CZ + " " + enabled);
        assertTrue("machine set-enabled failed: " + resp,
                String.valueOf(enabled).equals(Reply.of(resp).text("enabled")));
    }

    private static String readBeaconList() throws Exception {
        return exec("artest beacon list " + planetDim);
    }

    /**
     * Every beacon-registry record since {@code mark}, for a failure message.
     *
     * <p>Built to survive its own failure: if the mark could not be parsed, or the records never
     * arrived, this says SO rather than returning an empty string that reads as "the registry did
     * nothing" — which is one of the three answers the caller is trying to tell apart.</p>
     */
    private static String beaconEventsSince(String markReply) throws Exception {
        // `artest events mark` answers `seq`, not `mark`. The first version of this looked for the
        // latter and reported "no mark was taken" — which is the guard below doing its job: it said
        // it could not speak rather than returning an empty string that reads as "the registry did
        // nothing", one of the three answers this method exists to tell apart.
        Reply mark = Reply.of("artest events mark", markReply);
        if (!mark.has("seq")) {
            return "(no mark was taken, so nothing can be said about the sequence: " + markReply + ")";
        }
        String records = exec("artest events since " + mark.integer("seq"));
        // Asked of each record's own `type`. `contains("beacon_")` over the envelope is answered
        // by the INSTRUMENTS list, which names every registered recorder whether or not it wrote
        // anything — so the "no beacon record at all" branch below could never be reached, and
        // the three answers this method exists to tell apart collapsed into one.
        boolean anyBeacon = false;
        for (String record : Events.records(records)) {
            String type = Events.text(record, "type");
            anyBeacon |= type != null && type.startsWith("beacon_");
        }
        return anyBeacon ? records
                : "(no beacon record at all in " + records.length() + " bytes of events — either the "
                        + "break never reached production, or the recording mixins are not applied)";
    }

    /** True iff the dim's beacon-locations registry contains the triple
     *  (x, y, z). Walks each {@code [x,y,z]} entry in the {@code locations}
     *  array of {@code /artest beacon list}. */
    private static boolean beaconListContains(int x, int y, int z) throws Exception {
        String resp = readBeaconList();
        Reply list = Reply.of("artest beacon list", resp);
        assertTrue("beacon list response missing locations field: " + resp, list.has(LOCATIONS));
        for (int[] at : list.blockPosArray(LOCATIONS)) {
            if (at[0] == x && at[1] == y && at[2] == z) {
                return true;
            }
        }
        return false;
    }

    /**
     * The AR dimensions registered right now.
     *
     * <p>Asked of the probe, not scraped out of {@code ar planet list}. Nothing here is a claim
     * about that command's OUTPUT — it was only ever a convenient place to find the ids, and a
     * {@code DIM(\d+):} over it breaks on any change to how a planet line is captioned. Both read
     * the same source: {@code PlanetListCommand:28} iterates
     * {@code DimensionManager.getInstance().getRegisteredDimensions()}, which is exactly what
     * {@code artest dim list} reports as {@code arDimensions}.</p>
     */
    private static Set<Integer> arDims() throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (int dim : Reply.of("artest dim list", exec("artest dim list")).intArray("arDimensions")) {
            ids.add(dim);
        }
        return ids;
    }
}
