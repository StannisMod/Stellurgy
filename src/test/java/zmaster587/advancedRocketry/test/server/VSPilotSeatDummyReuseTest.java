package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.SeatMount;
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

        // The bare form is right here and nowhere else: this test PLACES the only pilot seat in the
        // world two statements above, and the reader's seat count is what says so in a failure.
        SeatMount first = SeatMount.firstLoadedSeat(
                cmd -> String.join("\n", client().execute(cmd)), 0);
        first.requireSeatFound("seat-mount must find the pilot seat just placed");

        SeatMount second = SeatMount.firstLoadedSeat(
                cmd -> String.join("\n", client().execute(cmd)), 0);
        second.requireSeatFound("seat-mount must find that same pilot seat again");

        assertEquals("a second mount on the same seat must REUSE its bound dummy, not spawn a "
                        + "twin (first=" + first.raw() + " second=" + second.raw() + ")",
                first.requireDummyId(), second.requireDummyId());
        assertTrue("the second response must say the dummy was reused: " + second.raw(),
                second.reused);
    }

}
