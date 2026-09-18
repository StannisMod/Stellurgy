package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.StationInfo;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Repro (finding C142) for the station-altitude redstone
 * control range.
 *
 * <p>{@code TileStationAltitudeController.update()}'s redstone branch set
 * {@code targetOrbitalDistance = Math.max(power*13+4, 190)}. Because the GUI slider's
 * maximum ({@code getTotalProgress()}) is 190, every redstone signal 0..14 floored to
 * 190 and only signal 15 reached 199 — so a redstone signal could never command a LOW
 * altitude. The fix uses {@code Math.min}, so the target spans 4..190 across the
 * signal range (matching the GUI's own 0..190).</p>
 *
 * <p>This drives the controller in redstone ON with no wiring ({@code
 * getStrongPower == 0}): the fixed {@code Math.min(4, 190) = 4} (a low altitude); the
 * buggy {@code Math.max(4, 190) = 190} (floored). Pins the contract "a low/absent
 * redstone signal selects a low altitude, below the 190 floor". Server-tier: the
 * effect is a server-side station state field, no client surface.</p>
 */
public class AltitudeControllerRedstoneSelectsLowAltitudeTest extends AbstractSharedServerTest {

    private static final int SPACE_DIM = -2;
    private static final String STATION_ID = "id";

    @Test
    public void redstoneOnWithNoSignalSelectsLowAltitudeNotFloored190() throws Exception {
        exec("artest dim load " + SPACE_DIM);

        String create = exec("artest station create 0");
        assertTrue("station must create: " + create, Reply.of(create).ok());
        int stationId = extract(STATION_ID, create);

        StationInfo info = station(stationId);
        int cx = info.spawnX(), cy = 128, cz = info.spawnZ();

        exec("artest fill " + SPACE_DIM + " " + (cx - 1) + " " + cy + " " + (cz - 1)
                + " " + (cx + 1) + " " + cy + " " + (cz + 1) + " minecraft:air");
        String place = exec("artest place " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " advancedrocketry:altitudeController");
        assertTrue("altitude controller must place: " + place, Reply.of(place).bool("placed", false));

        // Put the controller into redstone-ON mode (default is OFF). With no redstone
        // wiring around it, getStrongPower(pos) == 0.
        String setRs = exec("artest station controller-set-redstone " + SPACE_DIM + " "
                + cx + " " + cy + " " + cz + " ON");
        assertTrue("controller-set-redstone must succeed: " + setRs, Reply.of(setRs).ok());

        // A few ticks: the redstone branch writes targetOrbitalDistance = f(power=0) each tick.
        exec("artest tile force-tick " + SPACE_DIM + " " + cx + " " + cy + " " + cz + " 3");

        StationInfo postInfo = station(stationId);
        int target = postInfo.targetOrbitalDistance();

        assertTrue("C142: with redstone ON and no signal (power 0), the altitude target must be "
                        + "a LOW altitude (Math.min gives 4), not floored to the GUI max 190 by the old "
                        + "Math.max. Got targetOrbitalDistance=" + target + " info=" + postInfo.raw(),
                target < 190);
    }

    /** What the server says about one station. */
    private static StationInfo station(int stationId) throws Exception {
        return StationInfo.byId(WorldCommandFixtures::exec, stationId);
    }

    private static int extract(String field, String s) {
        Reply reply = Reply.of(s);
        assertTrue("field `" + field + "` not found in: " + s, reply.has(field));
        return reply.integer(field);
    }
}
