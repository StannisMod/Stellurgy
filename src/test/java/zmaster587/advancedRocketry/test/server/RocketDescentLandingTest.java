package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;
import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.Events;


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

    /** The altitude the descent STARTS at, in blocks — the arrangement's own number, cited by the
     *  assertion that says gravity pulled the craft below it. */
    private static final double DESCENT_START_Y = 300.0;

    private static final int DESCENT_TIMER = 40; // mirrors EntityRocket.DESCENT_TIMER

    /** World ticks a one-tick transition (the gate flipping, the touchdown) is given to be
     *  announced: a deadline, never spent on a healthy run. */
    private static final int GATE_TICKS = 100;

    /** This class's reader of the server's ordered event log, stepped on the rockets' own world. */
    private final Events events =
            new Events(cmd -> ok(client().execute(cmd)), ticks -> GameTicks.advanceWorld(client(), 0, ticks));

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
        RocketFixture.assembleAt(site, cmd -> ok(client().execute(cmd)), "simple", 2, 10,
                "the craft is built in this volume and then teleported to its descent altitude");
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
        //   - the next onUpdate fires the gate -> setInFlight(true), which
        //     is recorded and awaited.
        final FixtureSite site = FixtureSite.openAir(0, 6100, 500);
        int baseX = site.x;
        int baseZ = site.z;
        int id = buildAndAssemble(site);
        forceLoadChunksAround(0, baseX, baseZ);

        // Marked before the set-state: the gate may fire on the very next tick.
        long mark = events.markInstrumented();
        ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=false ticksExisted=" + (DESCENT_TIMER + 1)
                + " posY=300 motionY=0"));

        // Linked on the gate's own write: `setInFlight` is the one mutator, and the set-state above
        // wrote `false` through it, so only the gate can put a `true` for this rocket in the window.
        events.awaitRecordWithFields(mark, "rocket_flight_set",
                "the descent gate must set this rocket in flight under real ticking", GATE_TICKS,
                "e", String.valueOf(id), "inFlight", "true");
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

        long mark = events.markInstrumented();
        String set = ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=false ticksExisted=5 posY=300 motionY=0"));
        int ticksBefore = gi(TICKS_EXISTED, set, "ticksExisted as set");

        // WINDOW: ticksExisted is read on both sides of this stretch. The rocket's own counter
        // advancing proves the gate was ASKED (it runs in the same onUpdate), and its staying at or
        // below the timer proves it was asked only where it must answer no. Overshoot can only push
        // the counter past the timer and turn this red; it can never make a firing gate look idle.
        GameTicks.advanceWorld(client(), 0, 5);

        RocketInfo info = rocketInfo(id);
        assertTrue("the rocket must have been ticked across the window, or the gate was never asked"
                + " (ticksExisted " + ticksBefore + " -> " + info.ticksExisted + ")",
                info.ticksExisted > ticksBefore);
        assertTrue("ticksExisted should remain below the descent timer "
                + "(ticksExisted " + ticksBefore + " -> " + info.ticksExisted
                + ", DESCENT_TIMER=" + DESCENT_TIMER + ")",
                info.ticksExisted <= DESCENT_TIMER);
        assertFalse("isInFlight must NOT be set before descent timer expires: " + info.raw(),
                info.inFlight);
        assertFalse("and nothing may have set it in flight and back inside the window: "
                        + events.since(mark, "rocket_flight_set"),
                Events.anyRecordHasAll(events.since(mark, "rocket_flight_set"),
                        "e", String.valueOf(id), "inFlight", "true"));
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

        String set = ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=true ticksExisted=" + (DESCENT_TIMER + 5)
                + " posY=" + DESCENT_START_Y + " motionY=0"));
        double posYBefore = Reply.of(set).number("posY");

        // WINDOW: posY is read by the set-state itself and again after this stretch, and the claim
        // is that it FELL. The bar is "any fall at all", so overshoot adds descent without being
        // able to turn a craft that does not fall into one that does.
        GameTicks.advanceWorld(client(), 0, 5);

        double posYAfter = rocketInfo(id).posY;
        assertTrue("gravity must have pulled the rocket downwards under real ticking (posY "
                + posYBefore + " -> " + posYAfter + ")",
                posYAfter < posYBefore);
    }

    @Test
    public void landedEventFiresOnGroundCollisionUnderRealTicks_realTick() throws Exception {
        // Drive the line-1284 landed branch via REAL ticking with the
        // rocket's chunk force-loaded:
        //   - 5×5 stone floor at the site's own Y
        //   - orbit=true, flight=true, posY=siteY+2, motionY=-10
        //   - move() collides with stone -> RocketLandedEvent, which is awaited.
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

        long mark = events.mark();
        ok(client().execute("artest rocket set-state " + id
                + " orbit=true flight=true ticksExisted=" + (DESCENT_TIMER + 5)
                + " posY=" + (baseY + 2) + " motionY=-10"));

        // Linked on the landing Forge publishes (RocketLandedEvent, recorded server-side), narrowed
        // to this rocket: the shared server may be landing a sibling scenario's craft in the window.
        events.awaitRecordWithFields(mark, "rocket_landed",
                "the rocket must touch down on the stone floor under real ticking", GATE_TICKS,
                "e", String.valueOf(id));

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
