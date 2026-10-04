package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.StationPads;
import org.junit.Test;

import dev.stannismod.stellurgy.test.RocketList;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * REAL rocket&rarr;station cause-effect for pad state.
 *
 * <p>{@link SpaceStationDockUndockTest} exercises the production
 * state-machine methods directly via thin probes (addLandingPad,
 * getNextLandingPad, setPadStatus). What it does NOT cover is the
 * production CALL-SITES of those methods — i.e. the CHAIN "a rocket
 * does X" &rarr; "station-side pad state flips". A regression that severed
 * one of those chains (e.g. a refactor that drops the setOccupied call
 * inside TileGuidanceComputer.getStationLocation) is invisible to the
 * dock/undock tests.</p>
 *
 * Cause-effect chains pinned here:
 *
 * <ol>
 *   <li><b>TileGuidanceComputer.overrideLandingStation(station)</b> —
 *       production code calls this when a rocket re-routes to land on a
 *       different station mid-flight (EntityRocket.java:1175 / 1195).
 *       Side effect: station's next free pad is marked occupied=true.</li>
 *   <li><b>The setOccupied call site is gated on commit=true</b>: a
 *       getNextLandingPad(false) preview must NOT flip the flag, but
 *       overrideLandingStation does pass commit=true. Pin the contract.</li>
 *   <li><b>Without an auto-land-enabled pad, the cause-effect is no-op</b>:
 *       getStationLocation falls through to getNextLandingPad(commit) but
 *       getNextLandingPad only considers auto-land-enabled pads — a station
 *       with all pads opt-out must NOT have any pad flipped occupied.</li>
 * </ol>
 *
 * Why this is "real depth" (vs SpaceStationDockUndockTest's API smoke):
 * the test invokes a PRODUCTION method on the rocket side and verifies
 * the STATION-side pad state changed. If a refactor moved the
 * setOccupied(true) call out of getStationLocation (e.g. into a separate
 * tick-handler), this test fails — the dock/undock probe tests would
 * still pass because they exercise the station-side bookkeeping
 * directly.
 */
public class RocketStationCauseEffectTest extends AbstractSharedServerTest {

    private static final String ROCKET_LIST_ID = "id";
    /** The station's own id. The regex this replaces anchored on the NEXT field so as not to match
     *  some other {@code id}; reading by name needs no such anchor. */
    private static final String STATION_ID_FROM_CREATE = "id";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        String assemble = RocketFixture.assembleAt(site, cmd -> ok(client().execute(cmd)),
                "simple", 2, 10,
                "the craft is built and flown in this volume");
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());

        String list = ok(client().execute("stellurgytest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    private int createStation() throws Exception {
        String resp = ok(client().execute("stellurgytest station create 0"));
        assertTrue("station create failed: " + resp, Reply.of(resp).ok());
        Reply created = Reply.of("stellurgytest station create", resp);
        assertTrue("could not parse station id: " + resp, created.has(STATION_ID_FROM_CREATE));
        return created.integer(STATION_ID_FROM_CREATE);
    }

    @Test
    public void overrideLandingStationFlipsPadOccupied() throws Exception {
        // Setup: a station with one auto-land-enabled pad.
        int stationId = createStation();
        ok(client().execute("stellurgytest station add-pad " + stationId + " 50 50 alpha"));
        ok(client().execute("stellurgytest station set-autoland " + stationId + " 50 50 true"));

        // Build a rocket. The rocket itself stays on overworld; we just
        // need its guidance computer to invoke overrideLandingStation.
        int rocketId = buildAndAssemble(FixtureSite.openAir(0, 2000, 500));

        // Production cause-effect under test:
        //   gc.overrideLandingStation(station)
        //     -> setFallbackDestination(spaceDim, getStationLocation(station, true))
        //     -> getStationLocation calls station.getNextLandingPad(true)
        //     -> next free auto-land pad gets setOccupied(true).
        String override = ok(client().execute(
                "stellurgytest rocket override-landing " + rocketId + " " + stationId));
        assertTrue("override-landing probe must succeed: " + override,
                Reply.of(override).ok());

        // STATION-side observable: alpha must now be occupied. If a
        // regression moved or removed the setOccupied call in
        // getStationLocation, this fails — even though SpaceStationDockUndockTest
        // (which talks to setPadStatus directly) still passes.
        StationPads.Pad alpha = pads(stationId).at(50, 50);
        assertTrue("after override-landing, pad alpha MUST be occupied=true: " + alpha.raw(),
                alpha.occupied);
    }

    @Test
    public void overrideLandingStationWithNoAutoLandPadIsNoOp() throws Exception {
        // Counter-test: station with a pad but auto-land NOT enabled.
        // getStationLocation falls into the `landingLoc.get == null` branch
        // -> calls getNextLandingPad(true) which filters by allowedForAutoLand.
        // No pad qualifies -> returns null -> setOccupied is NEVER reached.
        int stationId = createStation();
        ok(client().execute("stellurgytest station add-pad " + stationId + " 60 60 beta"));
        // intentionally NOT calling set-autoland — pad stays opt-out.

        int rocketId = buildAndAssemble(FixtureSite.openAir(0, 2100, 500));
        ok(client().execute("stellurgytest rocket override-landing " + rocketId + " " + stationId));

        // Pad beta must STILL be occupied=false because no auto-land
        // candidate was available.
        StationPads.Pad beta = pads(stationId).at(60, 60);
        assertFalse("override-landing on station with no auto-land pads must NOT "
                        + "mark beta occupied: " + beta.raw(),
                beta.occupied);
    }

    @Test
    public void overrideLandingStationConsumesExactlyOnePadEvenAcrossManyCandidates() throws Exception {
        // Three auto-land pads. After ONE call to override-landing, exactly
        // ONE pad must be occupied — not all three, not zero. Pins the
        // "first match wins" / "no over-consumption" contract on the
        // getNextLandingPad(true) iteration loop.
        int stationId = createStation();
        for (int z : new int[]{70, 71, 72}) {
            ok(client().execute("stellurgytest station add-pad " + stationId + " 70 " + z + " p" + z));
            ok(client().execute("stellurgytest station set-autoland " + stationId + " 70 " + z + " true"));
        }

        int rocketId = buildAndAssemble(FixtureSite.openAir(0, 2200, 500));
        ok(client().execute("stellurgytest rocket override-landing " + rocketId + " " + stationId));

        // Counted over the PARSED pads. What stood here counted the substring `"occupied":true`
        // in the reply text and said so out loud — "the probe's output format is stable enough for
        // a substring count to be a reliable proxy" — which is a count of renderings, and it is
        // also the number a pad NAMED "occupied" would change.
        StationPads pads = pads(stationId);
        int occupiedCount = 0;
        for (StationPads.Pad pad : pads.all()) {
            if (pad.occupied) {
                occupiedCount++;
            }
        }
        assertEquals("exactly one pad must flip occupied — observed " + occupiedCount
                        + " in: " + pads.raw(),
                1, occupiedCount);
    }

    /** Every landing pad the station holds, addressable by position. */
    private StationPads pads(int stationId) throws Exception {
        return StationPads.byId(cmd -> ok(client().execute(cmd)), stationId);
    }
}
