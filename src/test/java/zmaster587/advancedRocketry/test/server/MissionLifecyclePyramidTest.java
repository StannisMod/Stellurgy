package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.MissionCompletion;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MissionResourceCollection lifecycle contract.
 *
 * <p>Pins the cause-effect of:
 * <ul>
 *   <li>{@code getProgress} reading {@code (now - startWorldTime) / duration}
 *       linearly, unbounded above 1.0, clamped at 0 below.</li>
 *   <li>{@code tickEntity} firing {@code onMissionComplete} + {@code setDead}
 *       at the tick where progress crosses 1.0.</li>
 *   <li>Natural {@code DimensionProperties.tick} loop prunes a completed
 *       mission from the satellite registry (cleanup contract).</li>
 * </ul>
 *
 * <p>Uses MissionGasCollection as the test vehicle — the simpler of the
 * two concrete subclasses (no asteroid chip required). The lifecycle
 * contract being pinned is in the abstract parent, so the choice of
 * concrete vehicle is an impl detail of the test, not the contract.</p>
 *
 * <p>Important: assertions read fields from the probe response of the
 * mutating call itself (advance / complete-now) rather than a follow-up
 * `state` call. Reason: the natural server tick prunes dead satellites
 * from the registry between commands, so a state lookup post-complete
 * races the natural tick. The mutating probe call computes its own
 * post-state snapshot atomically on the server thread — race-free.</p>
 */
public class MissionLifecyclePyramidTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    private static final String MISSION_ID = "missionId";
    private static final String PROGRESS = "progress";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private long buildRocketAndStartGasMission(int baseX, long duration) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 500);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");
        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];
        ok(client().execute("artest rocket assemble 0 " + bx + " " + by + " " + bz));

        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket after assemble: " + list, !built.isEmpty());
        int lastId = built.isEmpty() ? -1 : built.get(built.size() - 1).id;

        String start = ok(client().execute(
                "artest mission start-gas 0 " + lastId + " " + duration + " water"));
        assertFalse("start-gas must not error: " + start, start.contains("\"error\""));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId in start response: " + start, mmReply.has(MISSION_ID));
        return Long.parseLong(mmReply.text(MISSION_ID));
    }

    private double progressFromAdvance(long missionId, long ticks) throws Exception {
        String r = ok(client().execute("artest mission advance " + missionId + " " + ticks));
        assertFalse("advance must not error: " + r, r.contains("\"error\""));
        Reply pmReply = Reply.of(r);
        assertTrue("missing progress in advance response: " + r, pmReply.has(PROGRESS));
        return Double.parseDouble(pmReply.text(PROGRESS));
    }

    /** Progress fraction matches the (now - start) / duration ratio at
     *  the moment of the probe call. Window allowed for natural tick
     *  drift between commands (~ few ms / 50ms per tick). */
    @Test
    public void progressAdvancesLinearlyWithWorldTime() throws Exception {
        long mid = buildRocketAndStartGasMission(7000, 1000);
        double p1 = progressFromAdvance(mid, 250);
        assertTrue("after advance 250 / duration 1000, progress must be in [0.25, 0.35); got " + p1,
                p1 >= 0.25 && p1 < 0.35);
        double p2 = progressFromAdvance(mid, 250);
        assertTrue("after cumulative advance 500, progress must be in [0.5, 0.6); got " + p2,
                p2 >= 0.5 && p2 < 0.6);
    }

    /** Production's {@code getProgress} has no upper cap on the
     *  fraction it returns — pin the unbounded behaviour so a future
     *  cap surfaces here intentionally rather than silently. Use the
     *  advance response's progress field (atomic snapshot) so the
     *  natural-tick prune of the dead mission doesn't race the
     *  assertion. */
    @Test
    public void progressIsUnboundedAboveOne() throws Exception {
        long mid = buildRocketAndStartGasMission(7100, 1000);
        double p = progressFromAdvance(mid, 2500);
        assertTrue("after advance 2500 / duration 1000, progress must be ≥ 2.0; got " + p,
                p >= 2.0);
    }

    /** Below progress=1.0 the mission is not yet completable — verify
     *  via advance response's progress field. */
    @Test
    public void missionStaysAliveBelowProgressOne() throws Exception {
        long mid = buildRocketAndStartGasMission(7200, 1000);
        double p = progressFromAdvance(mid, 500);
        assertTrue("progress at 500/1000 must be < 1.0; got " + p, p < 1.0);
    }

    /** complete-now backdates + drives tickEntity once &rarr; the probe's
     *  atomic post-state report must show isDeadAfter=true AND
     *  completed=true (transition from alive&rarr;dead happened in this
     *  call). */
    @Test
    public void completionFiresAtProgressOne() throws Exception {
        long mid = buildRocketAndStartGasMission(7300, 1000);
        MissionCompletion resp = MissionCompletion.now(
                cmd -> ok(client().execute(cmd)), mid);
        assertTrue("complete-now must report transition (wasDeadBefore=false): " + resp.raw(),
                !resp.wasDeadBefore);
        assertTrue("complete-now must mark mission dead: " + resp.raw(),
                resp.isDeadAfter);
        assertTrue("complete-now must report completion fired: " + resp.raw(),
                resp.completed);
    }

    /** After completion, the DimensionProperties.tick loop removes the
     *  mission from the satellite registry — cleanup contract. Probes
     *  registry-cleanup as a player-visible contract: stale mission
     *  entries would leak the satellite map.
     *
     *  Drives the prune deterministically via {@code satellite
     *  force-tick-dim} rather than waiting on the natural tick, then
     *  polls {@code mission state} for the not-found response. */
    @Test
    public void completionPrunesMissionFromSatelliteRegistry() throws Exception {
        long mid = buildRocketAndStartGasMission(7400, 1000);
        MissionCompletion complete = MissionCompletion.now(
                cmd -> ok(client().execute(cmd)), mid);
        assertTrue("complete-now must succeed: " + complete.raw(),
                complete.completed);

        String state = "n/a";
        boolean pruned = false;
        for (int attempt = 0; attempt < 30; attempt++) {
            ok(client().execute("artest satellite force-tick-dim 0"));
            state = ok(client().execute("artest mission state " + mid));
            if (state.contains("\"error\":\"mission not found\"")) {
                pruned = true;
                break;
            }
        }
        assertTrue("post-completion state lookup must report mission not-found "
                        + "(after 30 dim-ticks): " + state, pruned);
    }
}
