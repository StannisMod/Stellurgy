package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One seat — one mount dummy, at EVERY dummy-producing path. The right-click mount recipe reuses
 * the seat's existing bound dummy; the {@code vs seat-mount} probe (the harness's stand-in for a
 * right-click on an assembled ship's subspace seat) must obey the same rule, or every test mount
 * leaks an extra dummy whose riderless twin clears the ship's pilot input each server tick and
 * fights the seated pilot's controls.
 *
 * <p>Runs on a bare, unassembled pilot seat block (the rule is about the seat↔dummy binding, not
 * about the ship), so no VS is needed and the pin holds in both suite configurations.</p>
 */
public class VSPilotSeatDummyReuseTest extends AbstractSharedServerTest {

    private static final String DUMMY_ID = "dummyId";

    @Test
    public void theSeatMountProbeReusesTheSeatsSingleDummy() throws Exception {
        int x = 3000, y = FixtureSite.OPEN_AIR_Y, z = 3000;
        String warmup = String.join("\n", client().execute(
                "artest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4)));
        assertTrue("chunk warmup failed: " + warmup, warmup.contains("\"ok\":true"));
        String place = String.join("\n", client().execute("artest fill 0 "
                + x + " " + y + " " + z + " " + x + " " + y + " " + z
                + " advancedrocketry:pilotSeat"));
        assertTrue("placing the pilot seat failed: " + place, place.contains("\"ok\":true"));

        String first = String.join("\n", client().execute("artest vs seat-mount 0"));
        assertTrue("seat-mount must find the pilot seat: " + first,
                first.contains("\"seatFound\":true"));
        int firstId = dummyId(first);

        String second = String.join("\n", client().execute("artest vs seat-mount 0"));
        assertTrue("seat-mount must find the pilot seat again: " + second,
                second.contains("\"seatFound\":true"));
        int secondId = dummyId(second);

        assertEquals("a second mount on the same seat must REUSE its bound dummy, not spawn a "
                        + "twin (first=" + first + " second=" + second + ")",
                firstId, secondId);
        assertTrue("the second response must say the dummy was reused: " + second,
                second.contains("\"reused\":true"));
    }

    private int dummyId(String json) {
        Reply mReply = Reply.of(json);
        assertTrue("expected a dummyId in: " + json, mReply.has(DUMMY_ID));
        return Integer.parseInt(mReply.text(DUMMY_ID));
    }
}
