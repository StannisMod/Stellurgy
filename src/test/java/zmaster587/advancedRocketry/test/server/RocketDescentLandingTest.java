package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;
import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.GameTicks;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * descent + landing under the REAL server
 * tick loop.
 *
 * <p>Earlier drafts of this suite drove {@code rocket.onUpdate()} via a
 * synthetic {@code /artest rocket tick} probe. That worked for the
 * state-machine gates but skirted the production environment: real
 * collision data depends on neighbour chunks being loaded, real
 * motion-integration happens on the server tick thread, and the
 * landing-detection branch (line 1284 of {@code EntityRocket.onUpdate})
 * relies on {@code move()} consulting the chunk's collision shapes.
 *
 * <p>The reliable substitute is a Forge chunk-loading ticket
 * (registered via {@code WorldEvents} mod-side, dispensed by the new
 * {@code /artest chunk forceload} probe). Holding the chunk hot lets
 * the headless dedicated server tick the rocket entity through its
 * production code paths exactly as a real game session would.
 * {@link zmaster587.advancedRocketry.test.GameTicks#await} blocks the
 * test thread until the world's own clock has advanced by the requested
 * number of ticks — the waiting happens in the test jvm, because a
 * command handler runs on the very thread that advances that clock.
 *
 * <p>Test method names suffixed {@code _realTick} to make it explicit
 * which path is exercised.
 */
public class RocketDescentLandingTest extends AbstractSharedServerTest {

    private static final int DESCENT_TIMER = 40; // mirrors EntityRocket.DESCENT_TIMER

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    /** The field the TICK reply answers with — that verb's own, not {@code rocket info}'s. */
    private static final String TICKS_EXISTED = "ticksExisted";
    private static final String LANDED_COUNT = "landed";
    /** The forceload ticket the chunk-ticket claims are about, and the array it lives in. */
    private static final String TICKETS = "tickets";
    private static final String TICKET_KEY = "0:100:100";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> ok(client().execute(cmd)), id);
    }

    private static int gi(String field, String s, String label) {
        Reply reply = Reply.of(s);
        assertTrue("could not parse " + label + ": " + s, reply.has(field));
        return reply.integer(field);
    }

    // No per-test cleanup of chunk tickets: releasing a Forge chunk
    // ticket on an inhabited chunk has been observed to stall the
    // shared dedicated-server harness for >30 s (likely chunk-unload
    // bookkeeping over entities still in those chunks). We let the
    // tickets leak for the duration of the class — they're freed
    // implicitly when the harness shuts down at @AfterClass. Each test
    // picks a position-disjoint chunk so leaked tickets do not bleed
    // into other tests.

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built in is EMPTY. The site stands in open air, so
        // this ASSERTS rather than digs, and the fill inside it force-loads every chunk in the box.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built in this volume and then teleported to its descent altitude");
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

    /** Force-load a 3×3 grid of chunks centered on (worldX, worldZ) in
     *  dim {@code dim}. Three chunks each side covers any rocket descent
     *  within ~16 blocks of the center — generous given the rocket sits
     *  in a single chunk. */
    private void forceLoadChunksAround(int dim, int worldX, int worldZ) throws Exception {
        int cx = worldX >> 4;
        int cz = worldZ >> 4;
        for (int dxc = -1; dxc <= 1; dxc++) {
            for (int dzc = -1; dzc <= 1; dzc++) {
                ok(client().execute(
                        "artest chunk forceload " + dim + " " + (cx + dxc) + " " + (cz + dzc)));
            }
        }
    }

    @Test
    public void rocketTickProbeReportsTicksExistedInResponse() throws Exception {
        // Probe-surface sanity: /artest rocket tick must succeed and
        // expose ticksExisted in the response. Used by the explicit
        // synthetic-tick path in Phase 5 (failure-mode tests).
        int id = buildAndAssemble(FixtureSite.openAir(0, 6000, 500));
        String tickResp = ok(client().execute("artest rocket tick " + id + " 5"));
        assertTrue("tick probe must succeed: " + tickResp,
                Reply.of(tickResp).ok());
        assertTrue("tick probe response must expose ticksExisted: " + tickResp,
                Reply.of(tickResp).has("ticksExisted"));
        int t = gi(TICKS_EXISTED, tickResp, "ticksExisted from tick response");
        assertTrue("ticksExisted must be non-negative: " + t, t >= 0);
    }

    @Test
    public void descentTimerGateFlipsInFlightUnderRealTicks_realTick() throws Exception {
        // Production gate (EntityRocket.onUpdate line 1047):
        //   if (ticksExisted > DESCENT_TIMER && isInOrbit() && !isInFlight())
        //       setInFlight(true);
        //
        // Setup under REAL server ticking:
        //   - assemble + force-load the rocket's chunk
        //   - state: orbit=true, flight=false, ticksExisted=DESCENT_TIMER+1
        //   - await 5 real ticks -> onUpdate runs at least once ->
        //     gate fires -> isInFlight flips to true.
        final FixtureSite site = FixtureSite.openAir(0, 6100, 500);
        int baseX = site.x;
        int baseZ = site.z;
        int id = buildAndAssemble(site);
        forceLoadChunksAround(0, baseX, baseZ);

        ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=false ticksExisted=" + (DESCENT_TIMER + 1)
                + " posY=300 motionY=0"));

        GameTicks.advanceWorld(client(), 0, 5);

        RocketInfo info = rocketInfo(id);
        assertTrue("descent gate must flip isInFlight under real ticking: " + info.raw(),
                info.inFlight);
    }

    @Test
    public void tickBeforeDescentTimerKeepsFlightOff_realTick() throws Exception {
        // Counter-test under real ticking: with ticksExisted well below
        // DESCENT_TIMER, even a few real server ticks must NOT flip the
        // gate. Pins that the gate is correctly conditional on the timer.
        final FixtureSite site = FixtureSite.openAir(0, 6200, 500);
        int baseX = site.x;
        int baseZ = site.z;
        int id = buildAndAssemble(site);
        forceLoadChunksAround(0, baseX, baseZ);

        ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=false ticksExisted=5 posY=300 motionY=0"));

        GameTicks.advanceWorld(client(), 0, 5);

        RocketInfo info = rocketInfo(id);
        // ticksExisted will have advanced by up to ~5 under real ticking;
        // the gate threshold (DESCENT_TIMER=40) is still not crossed, so
        // isInFlight remains false.
        assertTrue("ticksExisted should remain below the descent timer "
                + "(have " + info.ticksExisted + ", DESCENT_TIMER=" + DESCENT_TIMER + ")",
                info.ticksExisted <= DESCENT_TIMER);
        assertFalse("isInFlight must NOT be set before descent timer expires: " + info.raw(),
                info.inFlight);
    }

    @Test
    public void inFlightDescentApplesGravityUnderRealTicks_realTick() throws Exception {
        // Production line 1260: when isInOrbit AND descending (motionY
        // negative or burning false), motionY decreases on every tick.
        // After 5 real ticks the rocket's posY must have dropped below
        // its starting altitude. Pin: gravity actually integrates.
        final FixtureSite site = FixtureSite.openAir(0, 6400, 500);
        int baseX = site.x;
        int baseZ = site.z;
        int id = buildAndAssemble(site);
        forceLoadChunksAround(0, baseX, baseZ);

        ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=true ticksExisted=" + (DESCENT_TIMER + 5)
                + " posY=300 motionY=0"));

        GameTicks.advanceWorld(client(), 0, 5);

        double posYAfter = rocketInfo(id).posY;
        assertTrue("gravity must have pulled the rocket downwards under "
                + "real ticking (posY=" + posYAfter + ", started at 300)",
                posYAfter < 300.0);
    }

    @Test
    public void landedEventFiresOnGroundCollisionUnderRealTicks_realTick() throws Exception {
        // Drive the line-1284 landed branch via REAL ticking with the
        // rocket's chunk force-loaded:
        //   - 5×5 stone floor at the site's own Y
        //   - orbit=true, flight=true, posY=siteY+2, motionY=-10
        //   - wait 6 ticks -> move() collides with stone -> RocketLandedEvent.
        //
        // THE FLOOR THIS LANDS ON IS BUILT HERE, so the site needs no terrain — which is why it is
        // an open-air site like every other scenario in this class and not a declared ground one.
        // The -10 step does not overshoot the plate: Entity.move gathers collision boxes over the
        // SWEPT box (getEntityBoundingBox().expand(x, y, z)) and clamps the descent to the first one
        // it meets, so a one-block plate stops a ten-block fall.
        final FixtureSite site = FixtureSite.openAir(0, 6300, 500);
        int baseX = site.x;
        int baseY = site.y;
        int baseZ = site.z;
        int id = buildAndAssemble(site);
        forceLoadChunksAround(0, baseX, baseZ);

        ok(client().execute("artest fill 0 " + (baseX - 2) + " " + baseY + " " + (baseZ - 2)
                + " " + (baseX + 2) + " " + baseY + " " + (baseZ + 2) + " minecraft:stone"));

        String countsBefore = ok(client().execute("artest rocket event-counts-full"));
        int landedBefore = gi(LANDED_COUNT, countsBefore, "landed before");

        ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=true ticksExisted=" + (DESCENT_TIMER + 5)
                + " posY=" + (baseY + 2) + " motionY=-10"));

        GameTicks.advanceWorld(client(), 0, 6);

        String countsAfter = ok(client().execute("artest rocket event-counts-full"));
        int landedAfter = gi(LANDED_COUNT, countsAfter, "landed after");
        assertTrue("RocketLandedEvent must fire on ground collision under real ticks: "
                + landedBefore + " -> " + landedAfter, landedAfter > landedBefore);

        RocketInfo info = rocketInfo(id);
        assertFalse("production must clear isInFlight on landing: " + info.raw(), info.inFlight);
        assertFalse("production must clear isInOrbit on landing: " + info.raw(), info.inOrbit);
    }

    @Test
    public void dismantleAfterAssemblePastesBlocksBackAtRocketFootprint() throws Exception {
        // EntityRocket.deconstructRocket (line 1898) calls
        //   storage.pasteInWorld(world, posX - sizeX/2, posY, posZ - sizeZ/2)
        // Verify the storage chunk's contents land back in the world.
        // (This test doesn't need real ticking — dismantle is synchronous —
        // but it does need the destination chunk loaded, which the fill
        // probe pulls in automatically.)
        final FixtureSite site = FixtureSite.openAir(0, 6500, 500);
        int id = buildAndAssemble(site);

        int posY = (int) rocketInfo(id).posY;

        String dismantleResp = ok(client().execute("artest rocket dismantle " + id));
        assertTrue("dismantle must succeed: " + dismantleResp,
                Reply.of(dismantleResp).ok());

        boolean foundNonAir = false;
        outer:
        for (int dx = -2; dx <= 2 && !foundNonAir; dx++) {
            for (int dz = -2; dz <= 2 && !foundNonAir; dz++) {
                for (int dy = 0; dy <= 4 && !foundNonAir; dy++) {
                    String blockResp = ok(client().execute(
                            "artest block at 0 " + (site.x + dx) + " " + (posY + dy)
                                    + " " + (site.z + dz)));
                    // the producer always writes `isAir` for a loaded dimension, and this asks
                    // about dim 0 — so the one shape that omits it (`world not loaded`) is not
                    // reachable from here. A default would let a probe that stopped answering
                    // read as five cubic metres of air and fail the claim below for the wrong
                    // reason.
                    if (!Reply.of(blockResp).bool("isAir")) {
                        foundNonAir = true;
                        break outer;
                    }
                }
            }
        }
        assertTrue("dismantle must paste at least one non-air block back",
                foundNonAir);
    }

    @Test
    public void chunkAnchorProbeRoundTrips() throws Exception {
        // Probe-surface sanity: forceload + release for a single chunk
        // must succeed and return ok=true. The list endpoint reflects
        // the active ticket set. release-all clears them.
        String fl = ok(client().execute("artest chunk forceload 0 100 100"));
        assertTrue("forceload must succeed: " + fl, Reply.of(fl).ok());

        String list = ok(client().execute("artest chunk list"));
        // MEMBERSHIP of the ticket array, asked of the array. As a substring the key was also
        // matched inside a LONGER key — `0:100:1000` contains `0:100:100` — so the negative
        // claim below could fail for a neighbour's ticket and the positive one pass on it.
        assertTrue("list must include the ticket key: " + list,
                Reply.of("artest chunk list", list).holdsText(TICKETS, TICKET_KEY));

        String rel = ok(client().execute("artest chunk release 0 100 100"));
        assertTrue("release must succeed: " + rel, Reply.of(rel).ok());

        String listAfter = ok(client().execute("artest chunk list"));
        assertFalse("list must not include released ticket: " + listAfter,
                Reply.of("artest chunk list", listAfter).holdsText(TICKETS, TICKET_KEY));
    }
}
