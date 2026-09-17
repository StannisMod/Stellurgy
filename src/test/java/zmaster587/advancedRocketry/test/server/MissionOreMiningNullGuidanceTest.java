package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.MissionCompletion;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — C049 reproduction + regression guard.
 *
 * <p>Contract under test: {@link zmaster587.advancedRocketry.mission.MissionOreMining#onMissionComplete()}
 * must not crash the server tick when the rocket's guidance computer is missing.
 * The unconditional chip-refill step dereferences
 * {@code rocketStorage.getGuidanceComputer()} with no null guard; the only guard
 * sits inside the {@code drillingPower != 0f} harvest branch, so a mission that
 * completes with {@code drillingPower == 0} and a null guidance computer NPEs on
 * the {@code ServerTickEvent} thread (uncaught → server-tick crash).</p>
 *
 * <p>The probe reproduces the "no guidance computer" runtime state (as a reload
 * that dropped the tile, or a rocket that never had one) by stripping the
 * guidance computer from the mission's rocket storage, then completes the
 * mission. Pre-fix the completion tick NPEs (surfaced via the {@code /artest}
 * error envelope); post-fix a hoisted top-of-method guard returns cleanly.</p>
 */
public class MissionOreMiningNullGuidanceTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String MISSION_ID = "missionId";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 720);
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
        return built.get(built.size() - 1).id;
    }

    private long startOreMission(int rocketId, float drillingPower) throws Exception {
        String start = ok(client().execute(
                "artest mission start-ore 0 " + rocketId + " 1000 " + drillingPower));
        assertFalse("start-ore must not error: " + start, start.contains("\"error\""));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId: " + start, mmReply.has(MISSION_ID));
        return Long.parseLong(mmReply.text(MISSION_ID));
    }

    /** The bug: drillingPower == 0 skips the harvest branch (the only null
     *  guard), so a null guidance computer NPEs at the unconditional chip
     *  refill. Completion must instead finish cleanly. */
    @Test
    public void oreCompletionWithNullGuidanceDrillingZeroDoesNotCrash() throws Exception {
        int rid = buildAndAssembleRocket(9300);
        long mid = startOreMission(rid, 0.0f);

        String strip = ok(client().execute("artest mission strip-guidance " + mid));
        assertTrue("strip-guidance failed: " + strip, strip.contains("\"ok\":true"));
        assertTrue("guidance computer must be gone: " + strip,
                strip.contains("\"hasGuidanceComputer\":false"));

        MissionCompletion complete = MissionCompletion.now(
                cmd -> ok(client().execute(cmd)), mid);
        assertFalse("completing an ore mission with a missing guidance computer "
                        + "must not NPE the server tick (C049): " + complete.raw(),
                complete.raw().contains("NullPointerException"));
        assertTrue("complete-now must report success: " + complete.raw(),
                complete.isDeadAfter);
    }

    /** Control: the sibling branch (drillingPower != 0 with a null guidance
     *  computer) already early-returns without crashing — post-fix both
     *  branches must remain crash-free, unifying the asymmetry. */
    @Test
    public void oreCompletionWithNullGuidanceDrillingNonZeroDoesNotCrash() throws Exception {
        int rid = buildAndAssembleRocket(9400);
        long mid = startOreMission(rid, 1.0f);

        String strip = ok(client().execute("artest mission strip-guidance " + mid));
        assertTrue("strip-guidance failed: " + strip, strip.contains("\"ok\":true"));

        MissionCompletion complete = MissionCompletion.now(
                cmd -> ok(client().execute(cmd)), mid);
        assertFalse("completing with a missing guidance computer must not NPE: " + complete.raw(),
                complete.raw().contains("NullPointerException"));
        assertTrue("complete-now must report success: " + complete.raw(),
                complete.isDeadAfter);
    }
}
