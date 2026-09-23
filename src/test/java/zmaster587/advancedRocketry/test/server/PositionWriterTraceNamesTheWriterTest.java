package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static final String TO = "to";
    private static final String BY = "by";
    private static final String COUNT = "count";

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
        events = new Events(this::exec, ticks -> GameTicks.advanceWorld(harness.client(), 0, ticks));
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

    /**
     * Put the fake player somewhere known. Nothing trails it: {@code ensure-fake} places him on the
     * server thread before it replies, and the position-write recorder writes from inside that
     * placement — so its record, if any, is in the log by the time the next command runs.
     */
    private void station(double y) throws Exception {
        String fake = exec("artest player ensure-fake 0 8.5 " + y + " 8.5");
        assertTrue("ensure-fake must succeed: " + fake, Reply.of(fake).ok());
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

        long mark = events.markInstrumented();
        station(260);

        String reply = events.await(mark, "pos_jump",
                "a 140-block placement of the test player must be recorded as a position write", 60);
        // Printed on the happy path too: this test calibrates an instrument, and what the instrument
        // actually SAYS is the thing under review — a green that nobody has ever read the output of
        // proves the field is present, not that it names anything.
        System.out.println("[pos-writer calibration] " + reply);

        String written = String.valueOf(Events.lastRecord(reply));
        assertTrue("the record must carry where the write put him: " + reply,
                !Double.isNaN(Events.number(written, TO)));
        assertEquals("the recorded destination must be the placement's own target; " + reply,
                260.0, Events.number(written, TO), 0.5);

        String trail = Events.text(written, BY);
        assertTrue("the record must carry a caller trail: " + reply, trail != null);
        assertFalse("an empty caller trail names no writer, which is the whole value of the record;"
                + " " + reply, trail.trim().isEmpty());
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

        long mark = events.markInstrumented();
        station(122);

        // Read at once: the write under test is synchronous with the command above, and the fake
        // player is never spawned into a world, so nothing else can move him in the meantime.
        String reply = exec("artest events since " + mark + " pos_jump");
        Events.assertInstrumentRan(reply, "entity_position_writers",
                "the 2-block move was seen by the recorder and filtered, not missed");
        Reply since = Reply.of("artest events since", reply);
        assertTrue("events since must report a count: " + reply, since.has(COUNT));
        assertEquals("a 2-block move is motion, not a jump, and must leave the timeline alone; "
                + reply, 0, since.integer(COUNT));
    }
}
