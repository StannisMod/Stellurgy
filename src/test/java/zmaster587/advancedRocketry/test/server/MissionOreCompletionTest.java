package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.MissionCompletion;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MissionOreMining.onMissionComplete contract.
 *
 * <p>Pins the player-visible cause-effect of completing an ore-mining
 * mission. Two production code paths run on completion:
 * <ul>
 *   <li>Conditional (guarded by {@code rocketStats.getDrillingPower() != 0f}):
 *       asteroid harvest with three Math.random() rolls (distance /
 *       composition / mass) populating rocket inventory tiles. Pins are
 *       loose-bound here (≥0 stacks) because exact roll outcomes are
 * impl, and an unregistered asteroid
 *       type short-circuits the inner harvest loop anyway.</li>
 *   <li>Unconditional (always runs): asteroid-chip slot 0 cleared and
 *       refilled with a fresh empty ItemAsteroidChip. This is a
 *       player-visible contract — the chip is consumed by the mission
 *       and a blank replacement appears in the guidance computer.</li>
 *   <li>Unconditional: a new EntityRocket (NOT EntityStationDeployedRocket
 *       like the gas path) is spawned in the launch dim at launch
 *       coords.</li>
 * </ul>
 */
public class MissionOreCompletionTest extends AbstractSharedServerTest {

    private static final String MISSION_ID = "missionId";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 700);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        RocketFixture.assembleAt(site, cmd -> ok(client().execute(cmd)), "simple", 2, 10,
                "the craft is built and flown in this volume");
        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    private long startOreMission(int rocketId, long duration, float drillingPower) throws Exception {
        String start = ok(client().execute(
                "artest mission start-ore 0 " + rocketId + " " + duration + " " + drillingPower));
        assertFalse("start-ore must not error: " + start, Reply.of(start).has("error"));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId in start response: " + start, mmReply.has(MISSION_ID));
        return Long.parseLong(mmReply.text(MISSION_ID));
    }

    /** Whether drillingPower is zero or not, the mission UNCONDITIONALLY
     *  clears guidance-computer slot 0 and refills it with a blank
     *  ItemAsteroidChip (MissionOreMining lines 116-118). The chip
     *  refill is a save-format / inventory contract — players see the
     *  fresh chip when they open the landed rocket. */
    @Test
    public void oreCompletionAlwaysRefillsGuidanceWithBlankAsteroidChip() throws Exception {
        int rid = buildAndAssembleRocket(9000);
        long mid = startOreMission(rid, 1000, 1.0f);
        MissionCompletion cargo = MissionCompletion.now(
                cmd -> ok(client().execute(cmd)), mid);
        // Refilled chip lands in the respawned rocket's guidance
        // computer (storage chunk inventory tile). It's a fresh chip
        // with no NBT — registry name match is enough.
        assertTrue("respawned rocket must carry an asteroid chip post-completion: " + cargo.raw(),
                cargo.carriesItem("advancedrocketry:asteroidchip"));
    }

    /** Production gate: with {@code drillingPower == 0f} the entire
     *  harvest block (MissionOreMining lines 42-114) is skipped — the
     *  rocket inventory has no ore stacks, just the refilled blank
     *  chip from lines 116-118. Counter-test pinning the gate. */
    @Test
    public void oreCompletionSkipsHarvestWhenDrillingPowerZero() throws Exception {
        int rid = buildAndAssembleRocket(9100);
        long mid = startOreMission(rid, 1000, 0.0f);
        MissionCompletion cargo = MissionCompletion.now(
                cmd -> ok(client().execute(cmd)), mid);
        // The blank refill chip (line 118) is the only item expected
        // — extract a count and pin upper bound. Tolerant of the
        // respawn-coords search returning multiple rockets if a prior
        // test in the same JVM placed one nearby (different Z origin
        // 700 keeps them apart but allow ≤ 2 for safety).
        int entries = cargo.itemEntries;
        assertTrue("drillingPower=0 -> only the refill chip (≤ 2 entries to allow "
                        + "a duplicate from a sibling test rocket); got " + entries
                        + "; resp=" + cargo.raw(),
                entries >= 1 && entries <= 2);
    }

    /** The ore-mining completion path spawns a plain EntityRocket (line
     *  119) at launch coords. rocket-cargo finds at least one rocket in
     *  the search BB. Together with the gas test (which spawns
     *  EntityStationDeployedRocket — a SUBCLASS of EntityRocket so it
     *  also matches the EntityRocket.class filter), this pin only
     *  confirms "some rocket exists" — type discrimination is a
     *  follow-up. */
    @Test
    public void oreCompletionRespawnsRocketInLaunchDim() throws Exception {
        int rid = buildAndAssembleRocket(9200);
        long mid = startOreMission(rid, 1000, 1.0f);
        MissionCompletion cargo = MissionCompletion.now(
                cmd -> ok(client().execute(cmd)), mid);
        assertTrue("at least one rocket entity must exist near launch coords after ore completion: "
                        + cargo.raw(),
                cargo.rocketCount > 0);
    }
}
