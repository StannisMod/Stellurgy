package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.StationInfo;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * space station lifecycle.
 *
 * Empty list &rarr; create real {@link zmaster587.advancedRocketry.stations.SpaceStationObject}
 * via probe &rarr; assert list/info reflect it.
 */
public class SpaceStationLifecycleSmokeTest extends AbstractHeadlessServerTest {

    private static final String ID_PATTERN = "id";

    @Test
    public void stationCreateRegistersAndPersistsForList() throws Exception {
        String emptyList = String.join("\n", client().execute("artest station list"));
        assertTrue("expected empty stations on fresh server, got: " + emptyList,
                emptyList.contains("\"stations\":[]"));

        String createResp = String.join("\n", client().execute("artest station create 0"));
        assertTrue("station create failed: " + createResp, createResp.contains("\"ok\":true"));

        Reply mReply = Reply.of(createResp);
        assertTrue("could not extract station id: " + createResp, mReply.has(ID_PATTERN));
        int stationId = Integer.parseInt(mReply.text(ID_PATTERN));

        String listAfter = String.join("\n", client().execute("artest station list"));
        assertTrue("created station " + stationId + " missing from list: " + listAfter,
                listAfter.contains("\"id\":" + stationId));

        // Read as NUMBERS: the substring form was a prefix, so `"orbitingPlanetId":0` was also
        // satisfied by a station orbiting dim 9701 and `"fuelAmount":0` by one holding 1000.
        StationInfo info = StationInfo.byId(
                cmd -> String.join("\n", client().execute(cmd)), stationId);
        assertEquals("station info wrong orbitingPlanetId: " + info.raw(),
                0, info.orbitingPlanetId);
        assertEquals("station info wrong default fuelAmount: " + info.raw(),
                0, info.fuelAmount());
    }
}
