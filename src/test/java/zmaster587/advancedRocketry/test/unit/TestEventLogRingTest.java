package zmaster587.advancedRocketry.test.unit;

import java.util.List;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import zmaster587.advancedRocketry.command.test.TestEventLog;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A chatty event type must not be able to silence a quiet one.
 *
 * <h2>The contract, and the failure it was written from</h2>
 *
 * <p>The log's whole promise is that an ABSENCE means something: "no such record since the mark" is
 * supposed to be a statement about the game. A single bounded ring shared by every type breaks that
 * promise silently — whichever type fires most often evicts everything else, and the log then
 * answers "it never happened" about a record it merely threw away.</p>
 *
 * <p>Measured 2026-08-21 on the client half: a ship crossing loads about a thousand chunks, the
 * chunk-applied records filled the ring, and the position writes a crossing test reads were gone
 * before anything asked for them. The reply reported {@code dropped:173}, so the log was HONEST
 * about being truncated and useless at the same time — an honest instrument still has to be an
 * instrument.</p>
 *
 * <p>Unit tier on purpose: this is a property of the container, and pinning it here means it cannot
 * regress behind a twenty-minute harness run.</p>
 */
public class TestEventLogRingTest {

    private static final String CHATTY = "chunk_data_applied";
    private static final String RARE = "pos_jump";

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
        // The log drops everything unless a recorder is subscribed — which is the OTHER half of not
        // being able to fake an answer, and here it has to be switched on for the subject to exist.
        TestEventLog.ServerRecorder.ensureRegistered();
    }

    @Before
    public void clearLog() {
        TestEventLog.reset();
    }

    /** The one record that matters survives ten times its own ring's worth of noise. */
    @Test
    public void aChattyTypeDoesNotEvictARareOne() {
        TestEventLog.record("server", 1L, RARE, "\"marker\":1");
        for (int i = 0; i < TestEventLog.CAPACITY_PER_TYPE * 10; i++) {
            TestEventLog.record("server", 2L, CHATTY, "\"cx\":" + i);
        }

        assertEquals("the rare record must still be readable after the chatty type overflowed"
                        + " many times over; dropped=" + TestEventLog.droppedByType(),
                1, TestEventLog.count(RARE));
        assertTrue("only the chatty type may have been truncated: " + TestEventLog.droppedByType(),
                TestEventLog.droppedByType().contains(CHATTY));
        assertTrue("the rare type must not appear among the evictions: "
                        + TestEventLog.droppedByType(),
                !TestEventLog.droppedByType().contains(RARE));
    }

    /**
     * Order survives the split.
     *
     * <p>Separate rings are the fix, and they are also the risk: a chain assertion reads ORDER, and
     * once records live in different rings the sequence is the only thing still carrying it. Without
     * this the fix above would trade a silent eviction for a silently reordered chain.</p>
     */
    @Test
    public void recordsComeBackInSequenceOrderAcrossTypes() {
        TestEventLog.record("server", 1L, "right_click_block", "");
        TestEventLog.record("server", 1L, CHATTY, "");
        TestEventLog.record("server", 2L, "sleep_in_bed", "");
        TestEventLog.record("server", 2L, CHATTY, "");
        TestEventLog.record("server", 3L, "player_wake_up", "");

        List<TestEventLog.Record> all = TestEventLog.since(0);
        assertEquals("every record must come back: " + TestEventLog.dump(), 5, all.size());
        for (int i = 1; i < all.size(); i++) {
            assertTrue("records must be ordered by sequence, but " + all.get(i - 1).seq
                            + " came before " + all.get(i).seq + "; dump=" + TestEventLog.dump(),
                    all.get(i - 1).seq < all.get(i).seq);
        }
        assertEquals("and the order must be the order they happened in",
                "[right_click_block, chunk_data_applied, sleep_in_bed, chunk_data_applied,"
                        + " player_wake_up]",
                zmaster587.advancedRocketry.test.Events.typesOf(typesReply(all)).toString());
    }

    /** Render the records the way the probe does, so the shared type extractor can read them. */
    private static String typesReply(List<TestEventLog.Record> records) {
        StringBuilder sb = new StringBuilder();
        for (TestEventLog.Record r : records) {
            sb.append("{\"type\":\"").append(r.type).append("\"}");
        }
        return sb.toString();
    }
}
