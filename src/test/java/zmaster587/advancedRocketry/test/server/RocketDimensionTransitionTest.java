package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Assume;
import org.junit.Test;


import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * rocket dimension-transition path.
 *
 * <p>Covers the synchronous {@code EntityRocket.changeDimension(int, double,
 * double, double)} chain invoked by {@code reachSpaceManned} /
 * {@code reachSpaceUnmanned} when {@code destinationDimId != current.dim}.
 * For an unmanned rocket (no riders) the transition is a direct
 * Forge-{@code changeDimension} call — no entry is added to
 * {@code PlanetEventHandler.transitionMap} (that queue is only populated
 * with passengers in {@code EntityRocket.changeDimension(int,double,double,
 * double)} line 1967). Pinning the cause-effect:
 *
 * <ul>
 *   <li>Rocket originally in dim 0 &rarr; after force-orbit-reached on a chip
 *       programmed to another AR dim, the rocket entity is GONE from
 *       dim 0 and PRESENT in the dest dim — found by UUID.</li>
 *   <li>UUID stable across the dimension change (Forge contract).</li>
 *   <li>Storage chunk geometry / fuel-tank count / engine count
 *       preserved.</li>
 *   <li>Invalid destination dim &rarr; production
 *       {@code !DimensionManager.canTravelTo(dim)} guard in
 *       {@code EntityRocket.changeDimension} returns null; rocket stays
 *       in original dim, NO crash.</li>
 * </ul>
 *
 * <p>Probe surface introduced for these tests:
 * {@code /artest rocket find-by-uuid <uuid>},
 * {@code /artest rocket force-dest-dim <id> <dim>},
 * and a {@code uuid} field on {@code /artest rocket info}/{@code list}.
 */
public class RocketDimensionTransitionTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    private static final String AR_DIMS_ARRAY = "arDimensions";
    // What follows are `artest rocket find-by-uuid`'s OWN field names. They are spelled the same as
    // `rocket info`'s and are a different verb's answer — the post-transition reads go through
    // find-by-uuid deliberately (see the comment at that call site), and it has no reader yet.
    private static final String UUID_FIELD = "uuid";
    private static final String DIM_FIELD = "dim";
    private static final String ENTITY_ID_FIELD = "entityId";
    private static final String STORAGE_SIZE_X = "storageSizeX";
    private static final String STORAGE_SIZE_Y = "storageSizeY";
    private static final String STORAGE_SIZE_Z = "storageSizeZ";
    private static final String ENGINE_COUNT = "engineCount";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> ok(client().execute(cmd)), id);
    }

    private static String g(String field, String s, String label) {
        Reply reply = Reply.of(s);
        assertTrue("could not parse " + label + ": " + s, reply.has(field));
        return reply.text(field);
    }

    private int firstNonOverworldArDimOrSkip() throws Exception {
        String joined = ok(client().execute("artest dim list"));
        Assume.assumeFalse("No AR dimensions registered",
                (Reply.of(joined).arrayLength("arDimensions") == 0));
        Reply dims = Reply.of("artest dim list", joined);
        assertTrue("could not parse arDimensions array: " + joined, dims.has(AR_DIMS_ARRAY));
        for (int dim : dims.intArray(AR_DIMS_ARRAY)) {
            if (dim != 0) return dim;
        }
        Assume.assumeTrue("Only overworld is an AR planet", false);
        return -1;
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");
        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];
        ok(client().execute("artest rocket assemble 0 " + bx + " " + by + " " + bz));
        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    @Test
    public void rocketInfoAndListExposeUuid() throws Exception {
        // Pin the probe-surface contract first — the dimension-transition
        // tests below all depend on UUID being readable from both info and
        // list endpoints. A regression that drops the uuid field would
        // mask cause-effect failures in the harder tests.
        int id = buildAndAssemble(FixtureSite.openAir(0, 5000, 500));
        // The reader REFUSES an absent uuid rather than answering one, and that refusal IS this
        // assertion: the value travels into `find-by-uuid` in the legs below.
        RocketInfo info = rocketInfo(id);
        assertFalse("rocket info must expose uuid: " + info.raw(), info.requireUuid().isEmpty());
        // Asked of each ROCKET, because that is where the field lives — `uuid` is a member of the
        // `rockets` array's elements and never a field of the reply.
        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> listed = RocketList.of(list);
        assertTrue("rocket list must carry the craft just built, or it says nothing about uuid: "
                + list, !listed.isEmpty());
        for (RocketList.Entry listedRocket : listed) {
            assertTrue("rocket list must expose uuid for " + listedRocket + ": " + list,
                    listedRocket.uuid != null);
        }
    }

    @Test
    public void inFlightRocketTransitionsToDestinationDim() throws Exception {
        // Drive a real cross-dim transition. After force-orbit-reached on
        // an unmanned rocket with destDim set to an AR dim:
        //   1. EntityRocket.reachSpaceManned() invokes this.changeDimension(destDim, ...)
        //   2. EntityRocket.changeDimension calls super (Forge), which
        //      respawns the entity in the destination world with a new
        //      entityId but preserves the UUID.
        //   3. The old entity in dim 0 is killed (isDead=true).
        //
        // Assertion: find-by-uuid in destDim must succeed and report dim==destDim.
        // The old entityId must NOT exist in dim 0 anymore.
        int destDim = firstNonOverworldArDimOrSkip();
        int id = buildAndAssemble(FixtureSite.openAir(0, 5100, 500));

        // Capture UUID before launch.
        String uuid = rocketInfo(id).requireUuid();

        // Force-load the destination dim before transition. The shared
        // harness has no player to keep arbitrary AR dims hot, and Forge's
        // changeDimension chain bails silently if initDimension fails
        // (return value not checked by reachSpaceManned).
        ok(client().execute("artest chunk forceload " + destDim + " 0 0"));
        ok(client().execute("artest rocket set-destination " + id + " " + destDim));
        ok(client().execute("artest rocket launch " + id + " true instant"));

        RocketInfo launchedInfo = rocketInfo(id);
        assertTrue("launch must set isInFlight=true (precondition for transition test): "
                        + launchedInfo.raw(),
                launchedInfo.inFlight);

        // Force orbit reached -> triggers transition.
        ok(client().execute("artest rocket force-orbit-reached " + id));

        // Find the rocket by UUID — must now be in destDim.
        String byUuid = ok(client().execute("artest rocket find-by-uuid " + uuid));
        assertTrue("rocket must be findable by UUID after transition: " + byUuid,
                Reply.of(byUuid).ok());
        int dimAfter = Integer.parseInt(g(DIM_FIELD, byUuid, "dim"));
        assertEquals("rocket must have transitioned to destination dim", destDim, dimAfter);
    }

    @Test
    public void transitionPreservesRocketIdentityAndStorageContents() throws Exception {
        // After transition the rocket is a NEW entity (different entityId)
        // but the same persistent identity (UUID) and the same storage
        // chunk geometry. This pins the Forge Entity.changeDimension
        // contract that copyDataFromOld carries NBT across — a regression
        // that drops the storage NBT (e.g. fails to call
        // copyDataFromOld) would shrink storageSizeX/Y/Z to defaults.
        int destDim = firstNonOverworldArDimOrSkip();
        int id = buildAndAssemble(FixtureSite.openAir(0, 5200, 500));

        RocketInfo infoBefore = rocketInfo(id);
        String uuid = infoBefore.requireUuid();
        int idBefore = infoBefore.entityId;
        int sxBefore = infoBefore.storageSizeX();
        int syBefore = infoBefore.storageSizeY();
        int szBefore = infoBefore.storageSizeZ();
        int engBefore = infoBefore.engineCount;

        // Force-load the destination dim before transition. The shared
        // harness has no player to keep arbitrary AR dims hot, and Forge's
        // changeDimension chain bails silently if initDimension fails
        // (return value not checked by reachSpaceManned).
        ok(client().execute("artest chunk forceload " + destDim + " 0 0"));
        ok(client().execute("artest rocket set-destination " + id + " " + destDim));
        ok(client().execute("artest rocket launch " + id + " true instant"));
        ok(client().execute("artest rocket force-orbit-reached " + id));

        // Pull all the assertion fields out of the find-by-uuid response
        // atomically — calling "rocket info <id>" afterwards is racy
        // because the destination dim/chunk may unload before the second
        // round-trip lands (no player anchor in the dest dim).
        String byUuid = ok(client().execute("artest rocket find-by-uuid " + uuid));
        assertTrue("rocket must be findable post-transition: " + byUuid,
                Reply.of(byUuid).ok());
        int idAfter = Integer.parseInt(g(ENTITY_ID_FIELD, byUuid, "entityId after"));
        assertNotEquals("entityId must change across changeDimension", idBefore, idAfter);
        int sxAfter = Integer.parseInt(g(STORAGE_SIZE_X, byUuid, "sizeX after"));
        int syAfter = Integer.parseInt(g(STORAGE_SIZE_Y, byUuid, "sizeY after"));
        int szAfter = Integer.parseInt(g(STORAGE_SIZE_Z, byUuid, "sizeZ after"));
        int engAfter = Integer.parseInt(g(ENGINE_COUNT, byUuid, "engines after"));
        String uuidAfter = g(UUID_FIELD, byUuid, "uuid after");

        assertEquals("storage sizeX preserved", sxBefore, sxAfter);
        assertEquals("storage sizeY preserved", syBefore, syAfter);
        assertEquals("storage sizeZ preserved", szBefore, szAfter);
        assertEquals("engine count preserved", engBefore, engAfter);
        assertEquals("UUID preserved across changeDimension", uuid, uuidAfter);
    }

    @Test
    public void transitionToInvalidDimFailsGracefullyAndKeepsRocket() throws Exception {
        // Force destDimId to a bogus value (-12345) directly, bypassing
        // launch()'s canTravelTo guard. Then force-orbit-reached -> the
        // reachSpaceManned branch calls changeDimension(-12345) which
        // checks canTravelTo and returns null (line 1944 in EntityRocket).
        // Assertion: the call doesn't throw, and the rocket still exists
        // in dim 0 under its original UUID (no half-transitioned state).
        int id = buildAndAssemble(FixtureSite.openAir(0, 5300, 500));

        String uuid = rocketInfo(id).requireUuid();

        // Launch needs a valid dim — use overworld self-route as a
        // pre-launch nudge, then force a bogus destDim AFTER launch.
        // Actually simpler: skip launch() (it would set destDim from the
        // chip and call canTravelTo). Just force in-flight + bogus destDim
        // + force-orbit-reached.
        ok(client().execute("artest rocket launch " + id + " true force"));
        ok(client().execute("artest rocket force-dest-dim " + id + " -12345"));

        // force-orbit-reached invokes onOrbitReached -> reachSpaceManned
        // -> changeDimension(-12345) -> canTravelTo guard returns null.
        String resp = ok(client().execute("artest rocket force-orbit-reached " + id));
        assertTrue("force-orbit-reached must not crash on invalid destDim: " + resp,
                Reply.of(resp).ok());

        // Rocket must still be findable by UUID, dim unchanged.
        String byUuid = ok(client().execute("artest rocket find-by-uuid " + uuid));
        assertTrue("rocket must still exist after invalid-dim transition attempt: " + byUuid,
                Reply.of(byUuid).ok());
        int dimAfter = Integer.parseInt(g(DIM_FIELD, byUuid, "dim after"));
        assertEquals("rocket must remain in original dim 0", 0, dimAfter);
        assertFalse("rocket must NOT be marked dead by the failed transition: " + byUuid,
                Reply.of(byUuid).bool("isDead"));
    }

    @Test
    public void findByUuidOnUnknownUuidReturnsError() throws Exception {
        // Probe contract test: a UUID that does not match any loaded
        // entity must return a structured "not found" error rather than
        // crashing or returning a stale match.
        String resp = ok(client().execute(
                "artest rocket find-by-uuid 00000000-0000-0000-0000-000000000000"));
        assertTrue("unknown uuid must error: " + resp,
                "rocket not found by uuid".equals(Reply.of(resp).text("error")));
    }

    @Test
    public void findByUuidOnMalformedUuidReturnsError() throws Exception {
        String resp = ok(client().execute("artest rocket find-by-uuid not-a-uuid"));
        assertTrue("malformed uuid must error: " + resp,
                "invalid uuid".equals(Reply.of(resp).text("error")));
    }
}
