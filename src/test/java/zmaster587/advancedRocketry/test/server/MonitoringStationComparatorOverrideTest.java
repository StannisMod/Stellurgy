package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * {@link
 * zmaster587.advancedRocketry.tile.infrastructure.TileRocketMonitoringStation#getComparatorOverride}
 * player-visible redstone contract.
 *
 * <p>A redstone comparator placed adjacent to a monitoring station
 * produces a 0..15 signal derived from the linked rocket's altitude
 * (production formula: {@code (int)(15 * rocket.getRelativeHeightFraction())}).
 * This is one of the few automation hooks in AR's flight cycle — players
 * use it to gate redstone circuits on rocket position (e.g. "open the
 * blast door when the rocket clears the launch tower"). A regression
 * that broke the link or inverted the height calc would silently break
 * those circuits without crashing.</p>
 *
 * <p>Pins:</p>
 * <ul>
 *   <li><b>No-rocket gate</b>: a freshly-placed monitor with no linked
 *       rocket reports {@code comparatorOverride == 0} (the {@code return 0}
 *       branch at the bottom of {@code getComparatorOverride}).</li>
 *   <li><b>Monotonic-altitude pin</b>: with a rocket linked, raising
 *       the rocket's {@code posY} strictly raises the comparator output.
 *       The exact numeric mapping (depends on {@code getTopBlock} and
 *       {@code getEntryHeight}) is impl; what the player sees is "higher
 *       rocket &rarr; stronger signal".</li>
 * </ul>
 */
public class MonitoringStationComparatorOverrideTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ENTITY_ID = "entityId";
    private static final String COMPARATOR_OVERRIDE = "comparatorOverride";
    private static final String LINKED_ENTITY_ID = "linkedEntityId";

    // Position-isolated x offsets per AbstractSharedServerTest contract.
    /**
     * The one anchor every coordinate in this class is relative to — the station sits at
     * {@code CY + 2} on a stone pad the test lays at {@code CY}, and the rocket's base is {@code CY}
     * itself. It was a hard-coded 64 until 2026-09-14; nothing here wants terrain, and because every
     * other Y is derived from this one, the whole class moves as a unit.
     */
    private static final int CY = zmaster587.advancedRocketry.test.FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 7000;
    private static final int CX_NO_ROCKET = 7400;
    private static final int CX_ALTITUDE = 7600;

    /**
     * Pin: a monitor with no linked rocket reports
     * {@code comparatorOverride == 0}. Catches a regression that
     * dereferences a null rocket and either NPEs or returns a junk
     * value — both would break players who place a monitor in advance
     * of building a rocket.
     */
    @Test
    public void unlinkedMonitorReportsZeroComparatorOverride() throws Exception {
        int mx = CX_NO_ROCKET, my = CY + 2, mz = CZ;
        // Ensure the chunk under the monitor is loaded.
        exec("artest fill 0 " + (mx - 1) + " " + CY + " " + (mz - 1) + " "
                + (mx + 1) + " " + CY + " " + (mz + 1) + " minecraft:stone");
        exec("artest place 0 " + mx + " " + my + " " + mz
                + " advancedrocketry:monitoringStation");

        String info = exec("artest infra monitor-info 0 " + mx + " " + my + " " + mz);
        assertTrue("monitor-info must succeed: " + info, Reply.of(info).ok());
        assertEquals("freshly-placed monitor with no linked rocket must "
                        + "report linkedEntityId=-1: " + info,
                -1, extract(info, LINKED_ENTITY_ID));
        assertEquals("unlinked monitor's getComparatorOverride must return "
                        + "0 (the null-rocket branch); " + info,
                0, extract(info, COMPARATOR_OVERRIDE));
    }

    /**
     * Pin: with a rocket linked, the monitor's comparator output
     * <em>strictly increases</em> as the rocket's {@code posY} rises.
     *
     * <p>Asserts only monotonicity, not exact values — the production
     * formula {@code (int)(15 * (posY - topBlockY) / (entryHeight - topBlockY))}
     * depends on the world-generated topBlock height and the configured
     * {@code orbit} (entry height), neither of which is part of the
     * player-visible contract. What the player sees is "higher rocket,
     * stronger redstone signal"; that's what we pin.</p>
     */
    @Test
    public void linkedMonitorComparatorOutputRisesWithRocketPosY() throws Exception {
        // Build + assemble a rocket near (CX_ALTITUDE, CY, CZ).
        int rocketId = buildAndAssemble(CX_ALTITUDE, CY, CZ);

        // Place the monitor at the same column (chunk-co-located so the
        // monitor's chunk and the rocket's chunk are always loaded
        // together; the rocket is the chunk-load source).
        int mx = CX_ALTITUDE + 5, my = CY + 2, mz = CZ;
        exec("artest fill 0 " + (mx - 1) + " " + CY + " " + (mz - 1) + " "
                + (mx + 1) + " " + CY + " " + (mz + 1) + " minecraft:stone");
        exec("artest place 0 " + mx + " " + my + " " + mz
                + " advancedrocketry:monitoringStation");

        // Link rocket to monitor via the infra-link probe.
        String linkResp = exec("artest infra link 0 " + mx + " " + my + " " + mz
                + " " + rocketId);
        assertTrue("infra link must succeed: " + linkResp,
                Reply.of(linkResp).bool("linked", false));

        // Read comparator with the rocket at a LOW altitude.
        exec("artest rocket set-state " + rocketId + " posY=68");
        String lowInfo = exec("artest infra monitor-info 0 " + mx + " " + my + " " + mz);
        int lowComparator = extract(lowInfo, COMPARATOR_OVERRIDE);

        // Read comparator with the rocket at a much HIGHER altitude.
        // 5000 is well above any plausible topBlock height in the
        // overworld and will produce a saturated reading.
        exec("artest rocket set-state " + rocketId + " posY=5000");
        String highInfo = exec("artest infra monitor-info 0 " + mx + " " + my + " " + mz);
        int highComparator = extract(highInfo, COMPARATOR_OVERRIDE);

        assertTrue("monitor comparator output must strictly increase as "
                        + "the linked rocket's posY rises — that's the "
                        + "player-visible 'higher rocket, stronger signal' "
                        + "contract for any redstone circuit gated off the "
                        + "monitor; lowPosY=68 -> comparator=" + lowComparator
                        + "  highPosY=5000 -> comparator=" + highComparator,
                highComparator > lowComparator);
    }

    // -- helpers ----------------------------------------------------------

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed` — the number the
        // pre-clear it replaces was throwing away. The site is in the band, so this ASSERTS rather
        // than digs, and its fill force-loads every chunk in the box, which is what the warmup did.
        zmaster587.advancedRocketry.test.FixtureSite.openAir(0, baseX, baseZ)
                .requireClear(cmd -> exec(cmd), 2, 10,
                        "the craft the monitoring station reports on stands in this volume");
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " simple");
        assertTrue("fixture build failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("no builderPos: " + fixture, bp != null);
        String assemble = exec("artest rocket assemble 0 "
                + bp[0] + " " + bp[1] + " " + bp[2]);
        assertTrue("assemble must succeed: " + assemble,
                Reply.of(assemble).ok());
        Reply eimReply = Reply.of(assemble);
        assertTrue("no entityId: " + assemble, eimReply.has(ENTITY_ID));
        return Integer.parseInt(eimReply.text(ENTITY_ID));
    }

    private static int extract(String src, String field) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.integer(field);
    }
}
