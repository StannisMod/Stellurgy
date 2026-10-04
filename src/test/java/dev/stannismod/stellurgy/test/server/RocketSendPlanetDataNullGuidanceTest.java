package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.RocketList;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

import dev.stannismod.stellurgy.test.ArrangementFailure;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A rocket's planet selection with no guidance to hand: the SENDPLANETDATA wire path below, and the
 * guidance computer's launch burn off any station.
 *
 * <p>Two defects on the {@code EntityRocket} planet-selection wire path:</p>
 * <ul>
 *   <li><b>Bug A (server null deref)</b>: the SENDPLANETDATA server handler in
 *       {@code useNetworkData} dereferences {@code storage.getGuidanceComputer()}
 *       with no null guard. A satellite-only rocket (which legitimately has no
 *       guidance computer) NPEs when a destination is confirmed — reachable in
 *       ordinary play.</li>
 *   <li><b>Bug B (reader underflow)</b>: the server writer emits its planet-id
 *       int only when a chip is present, while the reader unconditionally
 *       {@code readInt()}s — a short/empty payload underflows the buffer
 *       (per-packet FML slice framing makes it throw rather than misread).</li>
 * </ul>
 *
 * <p>Post-fix a null guard makes the chipless server handler a no-op, and a
 * reader length guard tolerates an empty payload — neither changes the wire
 * format.</p>
 */
public class RocketSendPlanetDataNullGuidanceTest extends AbstractSharedServerTest {

    private static final String ROCKET_LIST_ID = "id";
    private static final String THROWN = "thrown";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 760);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        RocketFixture.assembleAt(site, cmd -> ok(client().execute(cmd)), "simple", 2, 10,
                "the craft is built and flown in this volume");
        String list = ok(client().execute("stellurgytest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    /** Bug A: confirming a destination on a rocket with no guidance computer
     *  must not NPE the server. */
    @Test
    public void sendPlanetDataWithNullGuidanceDoesNotCrash() throws Exception {
        int rid = buildAndAssembleRocket(9500);

        String strip = ok(client().execute("stellurgytest rocket strip-guidance " + rid));
        assertTrue("strip-guidance failed: " + strip, Reply.of(strip).ok());
        assertTrue("guidance computer must be gone: " + strip,
                (!Reply.of(strip).bool("hasGuidanceComputer")));

        String resp = ok(client().execute("stellurgytest rocket send-planet-data " + rid + " 0"));
        assertTrue("send-planet-data failed: " + resp, Reply.of(resp).ok());

        Reply mReply = Reply.of(resp);
        assertTrue("thrown field missing: " + resp, mReply.has(THROWN));
        assertTrue("a SENDPLANETDATA packet for a guidance-computer-less rocket "
                        + "must not throw (Bug A); got " + mReply.reported(THROWN) + ": " + resp,
                "null".equals(mReply.text(THROWN)));
    }

    /** Bug B: the SENDPLANETDATA reader must tolerate an empty payload without
     *  underflowing the buffer. */
    @Test
    public void sendPlanetDataReaderToleratesEmptyPayload() throws Exception {
        int rid = buildAndAssembleRocket(9550);

        String resp = ok(client().execute("stellurgytest rocket planet-data-read-empty " + rid));
        assertTrue("planet-data-read-empty failed: " + resp, Reply.of(resp).ok());

        Reply mReply = Reply.of(resp);
        assertTrue("thrown field missing: " + resp, mReply.has(THROWN));
        assertTrue("reading a SENDPLANETDATA packet with an empty payload must not "
                        + "underflow the buffer (Bug B); got " + mReply.reported(THROWN) + ": " + resp,
                "null".equals(mReply.text(THROWN)));
    }

    /**
     * The null-station guard at {@code TileGuidanceComputer.getTransBodyInjection(...)}: a guidance
     * computer with a PLANET destination chip runs its in-space launch-burn calc in an empty grid
     * cell not over any station. {@code getSpaceStationFromBlockCoords} answers null there, and the
     * {@code destinationSpaceStation}/{@code INVALID_PLANET} short-circuit is bypassed because the
     * destination is a real planet. Formerly the unguarded
     * {@code currentSpaceStation.getOrbitingPlanetId()} crashed; the fix folds a null station into
     * the early return, degrading to no trans-body burn (the base launch-clearance burn still
     * applies).
     *
     * <p>The {@code guidance launch-seq} probe drives the real
     * {@code getLaunchSequence(spaceDimId, offSlotPos)} on a placed guidance computer — the burn calc
     * depends only on currentDim/currentPos/slot-0 chip, not on being rocket-embedded.</p>
     */
    @Test
    public void offSlotPlanetLaunchBurnInSpaceDimDegradesToBaseBurn() throws Exception {
        final int spaceDim = -2;
        exec("stellurgytest dim load " + spaceDim);

        String dims = exec("stellurgytest dim list");
        int destDim = Integer.MIN_VALUE;
        for (int d : Reply.of("stellurgytest dim list", dims).intArray("stellurgyDimensions")) {
            if (d != 0 && d != -1 && d != spaceDim) {
                destDim = d;
                break;
            }
        }
        ArrangementFailure.requireArranged(
                "a Stellurgy planet dim must be registered as the launch destination: " + dims,
                destDim != Integer.MIN_VALUE);

        // A station exists somewhere (models 'the player has a station'); it does NOT occupy the
        // off-slot cell we launch from.
        String create = exec("stellurgytest station create 0");
        assertTrue("station must create: " + create, Reply.of(create).ok());

        // getSpaceStationFromBlockCoords(4608,·,4608) reverse-maps to grid (2,2) → spiral index 18,
        // which no station here occupies. The X and Z are load-bearing; the Y is the open-air band.
        int x = 4608, y = FixtureSite.OPEN_AIR_Y, z = 4608;
        exec("stellurgytest fill " + spaceDim + " " + (x - 1) + " " + (y - 1) + " " + (z - 1)
                + " " + (x + 1) + " " + (y + 1) + " " + (z + 1) + " minecraft:air");
        String place = exec("stellurgytest place " + spaceDim + " " + x + " " + y + " " + z
                + " stellurgy:guidanceComputer");
        assertTrue("guidance computer must place: " + place,
                Reply.of(place).ok() || Reply.of(place).bool("placed"));

        String r = exec("stellurgytest guidance launch-seq " + spaceDim + " " + x + " " + y + " " + z + " " + destDim);
        assertTrue("probe must run: " + r, Reply.of(r).ok());
        // `has` is false for an absent field AND for a JSON null, which is the claim.
        assertFalse("launch position must be off any station (proves the null path): " + r,
                Reply.of("stellurgytest guidance launch-seq", r).has("stationAtPos"));
        assertTrue("chip must be programmed to the real planet dim so the INVALID_PLANET short-circuit "
                        + "is bypassed and the guarded null-station path is reached: " + r,
                String.valueOf(destDim).equals(Reply.of(r).text("chipDim")));
        assertTrue("null-station guard: off-slot in-space launch-burn must NOT throw — "
                        + "TileGuidanceComputer folds a null currentSpaceStation into the early return. Got: " + r,
                (!Reply.of(r).bool("threw")));
        Reply reply = Reply.of(r);
        assertTrue("field `burn` not found in: " + r, reply.has("burn"));
        int burn = reply.integer("burn");
        assertTrue("a real burn must be returned (not the probe's Integer.MIN_VALUE 'did not run' sentinel), "
                        + "and it must be non-negative — the base launch-clearance burn with no trans-body "
                        + "contribution: got burn=" + burn + " in " + r,
                burn != Integer.MIN_VALUE && burn >= 0);
    }
}
