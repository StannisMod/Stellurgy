package zmaster587.advancedRocketry.test.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The harness can NAME the code that moved a player, and it costs production nothing.
 *
 * <h2>The contract</h2>
 *
 * <p>An arrival un-seat is a multi-writer symptom — a re-seat, a pose teleport, a seat-glue snap and
 * vanilla's own passenger snap can all write one player's position within a few ticks — so a
 * diagnosis has to say WHICH writer fired, not merely that the position changed. This pins that the
 * suite can ask that question: a deliberate placement is recorded with its caller, ordinary motion
 * is not, and the instrument reports whether it is installed at all.</p>
 *
 * <h2>Why this test exists as its own class</h2>
 *
 * <p>It calibrates an instrument the crossing e2es depend on. A recorder nobody has watched fail is
 * a recorder nobody has calibrated, and the failure mode that has actually cost time here is not a
 * missing event — it is a recorder that was never running reporting an empty log which reads like a
 * finding. Hence the discrimination pair below: something that MUST be recorded, and something that
 * must NOT.</p>
 */
public class PositionWriterTraceNamesTheWriterTest {

    private static final Pattern TO = Pattern.compile("\"to\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern BY = Pattern.compile("\"by\":\"([^\"]*)\"");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    private Path workDir;
    private RealDedicatedServerHarness harness;
    private Events events;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-poswriter-");
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        events = new Events(this::exec, ticks -> GameTicks.await(harness.client(), 0, ticks));
    }

    @After
    public void stopServer() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    /** Put the fake player somewhere known, and let the placement that created him settle. */
    private void station(double y) throws Exception {
        String fake = exec("artest player ensure-fake 0 8.5 " + y + " 8.5");
        assertTrue("ensure-fake must succeed: " + fake, fake.contains("\"ok\":true"));
    }

    /**
     * A teleport is recorded, and the record names its caller.
     *
     * <p>The name is the whole point: "his position changed" was always observable and never enough.
     * The caller trail is what turns a red into a diagnosis, so it is asserted to be non-empty
     * rather than merely present.</p>
     */
    @Test(timeout = 180000)
    public void aDeliberatePlacementIsRecordedWithItsCaller() throws Exception {
        station(120);
        GameTicks.await(harness.client(), 0, 5);

        long mark = events.markInstrumented();
        station(260);

        String reply = events.await(mark, "pos_jump",
                "a 140-block placement of the test player must be recorded as a position write", 60);
        // Printed on the happy path too: this test calibrates an instrument, and what the instrument
        // actually SAYS is the thing under review — a green that nobody has ever read the output of
        // proves the field is present, not that it names anything.
        System.out.println("[pos-writer calibration] " + reply);

        Matcher to = TO.matcher(reply);
        assertTrue("the record must carry where the write put him: " + reply, to.find());
        assertEquals("the recorded destination must be the placement's own target; " + reply,
                260.0, Double.parseDouble(to.group(1)), 0.5);

        Matcher by = BY.matcher(reply);
        assertTrue("the record must carry a caller trail: " + reply, by.find());
        assertFalse("an empty caller trail names no writer, which is the whole value of the record;"
                + " " + reply, by.group(1).trim().isEmpty());
    }

    /**
     * A short move is NOT recorded.
     *
     * <p>Without this the previous test would pass on an instrument that records every write, which
     * is not an instrument — a rider is re-positioned by its mount every single tick, so an unfiltered
     * recorder would bury the one write that mattered under thousands that did not.</p>
     */
    @Test(timeout = 180000)
    public void ordinaryMotionIsNotRecordedAsAJump() throws Exception {
        station(120);
        GameTicks.await(harness.client(), 0, 5);

        long mark = events.markInstrumented();
        station(122);
        GameTicks.await(harness.client(), 0, 20);

        String reply = exec("artest events since " + mark + " pos_jump");
        Matcher count = COUNT.matcher(reply);
        assertTrue("events since must report a count: " + reply, count.find());
        assertEquals("a 2-block move is motion, not a jump, and must leave the timeline alone; "
                + reply, 0, Integer.parseInt(count.group(1)));
    }
}
