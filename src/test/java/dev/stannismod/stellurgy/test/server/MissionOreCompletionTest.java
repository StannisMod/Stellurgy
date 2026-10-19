package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    /** The registry name of the blank chip the completion refills the guidance computer with. */
    private static final String ASTEROID_CHIP = "stellurgy:asteroidchip";

    private static final String MISSION_ID = "missionId";

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** The rocket this scenario's press made, named by that press's own {@code rocket_born} record. */
    private int buildAndAssembleRocket(int baseX) throws Exception {
        return buildAndAssembleRocket(baseX, "simple");
    }

    private int buildAndAssembleRocket(int baseX, String variant) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 700);
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        return rockets.assembleAt(site, cmd -> ok(client().act(cmd)), variant, 2, 10,
                "the craft is built and flown in this volume");
    }

    private long startOreMission(int rocketId, long duration, float drillingPower) throws Exception {
        return startOreMission(rocketId, duration, drillingPower, "");
    }

    /** {@code chipType} empty: the chip names no registered asteroid, so no harvest can happen. */
    private long startOreMission(int rocketId, long duration, float drillingPower, String chipType)
            throws Exception {
        String start = ok(client().arrange(
                "stellurgytest mission start-ore 0 " + rocketId + " " + duration + " " + drillingPower
                        + (chipType.isEmpty() ? "" : " " + chipType)));
        assertFalse("start-ore must not error: " + start, Reply.of(start).has("error"));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId in start response: " + start, mmReply.has(MISSION_ID));
        return rockets.missionStarted(0, Long.parseLong(mmReply.text(MISSION_ID)));
    }

    /** An asteroid type the server has registered — one a chip can name for a harvest to happen. */
    private String aRegisteredAsteroidType() throws Exception {
        String[] types = read("stellurgytest mission asteroid-types").textArray("types");
        ArrangementFailure.requireArranged("the server must register at least one asteroid type",
                types.length > 0);
        return types[0];
    }

    /** Whether drillingPower is zero or not, the mission UNCONDITIONALLY
     *  clears guidance-computer slot 0 and refills it with a blank
     *  ItemAsteroidChip (MissionOreMining lines 116-118). The chip
     *  refill is a save-format / inventory contract — players see the
     *  fresh chip when they open the landed rocket.
     * Pins INV-MSN-05 (Ore completion always leaves a blank asteroid chip in guidance slot 0 on the respawned rocket).
     */
    @Pins("INV-MSN-05")
    @RedWitness(member = "MissionOreMining#onMissionComplete", at = "computer.setInventorySlotContents(0, new ItemStack(StellurgyItems.itemAsteroidChip));",
            replacedBy = "",
            fails = "respawned rocket must carry an asteroid chip post-completion", on = "2026-10-19")
    @Test
    public void oreCompletionAlwaysRefillsGuidanceWithBlankAsteroidChip() throws Exception {
        int rid = buildAndAssembleRocket(9000);
        long mid = startOreMission(rid, 1000, 1.0f);
        MissionCompletion cargo = MissionCompletion.now(commands(), rockets, mid);
        // Refilled chip lands in the respawned rocket's guidance
        // computer (storage chunk inventory tile). It's a fresh chip
        // with no NBT — registry name match is enough.
        assertTrue("respawned rocket must carry an asteroid chip post-completion: " + cargo.raw(),
                cargo.carriesItem(ASTEROID_CHIP));
    }

    /** Production gate: with {@code drillingPower == 0f} the harvest is skipped — the returned
     *  rocket carries the refilled blank chip and nothing else.
     *
     *  <p>Both missions fly a rocket with a cargo hold ({@code with-cargo}: the plain craft's only
     *  inventory is the guidance computer, whose slot the chip refill overwrites) and name the same
     *  REGISTERED asteroid on a full-data chip, so the only thing between them is the drilling
     *  power: the powered one brings back something that is not the chip (the harvest is reachable
     *  at all in this arrangement), the unpowered one does not.
     *  Without the first half, "only the chip came back" would hold for any reason a harvest could
     *  fail, the drilling power among them or not.</p>
     * Pins INV-MSN-06 (ore completion with zero drilling power performs no harvest).
     */
    @Pins("INV-MSN-06")
    @RedWitness(member = "MissionOreMining#onMissionComplete", at = "rocketStats.getDrillingPower() != 0f",
            replacedBy = "true",
            fails = "drillingPower=0 -> the returned rocket carries only the refill chip", on = "2026-10-19")
    @Test
    public void oreCompletionSkipsHarvestWhenDrillingPowerZero() throws Exception {
        String asteroid = aRegisteredAsteroidType();

        int powered = buildAndAssembleRocket(9100, "with-cargo");
        MissionCompletion harvested = MissionCompletion.now(commands(), rockets,
                startOreMission(powered, 1000, 1.0f, asteroid));
        boolean broughtBackOre = false;
        for (String item : harvested.itemIds()) {
            broughtBackOre |= !ASTEROID_CHIP.equals(item);
        }
        assertTrue("drillingPower=1 on asteroid '" + asteroid + "' -> the returned rocket carries a harvest;"
                + " resp=" + harvested.raw(), broughtBackOre);

        int unpowered = buildAndAssembleRocket(9300, "with-cargo");
        MissionCompletion cargo = MissionCompletion.now(commands(), rockets,
                startOreMission(unpowered, 1000, 0.0f, asteroid));
        String[] items = cargo.itemIds();
        assertTrue("drillingPower=0 -> the returned rocket carries the refill chip; resp=" + cargo.raw(),
                items.length >= 1);
        for (String item : items) {
            assertTrue("drillingPower=0 -> the returned rocket carries only the refill chip, not "
                    + item + "; resp=" + cargo.raw(), ASTEROID_CHIP.equals(item));
        }
    }

    /** The ore-mining completion path spawns a plain EntityRocket (line
     *  119) at launch coords — not the station-deployed craft the gas
     *  path returns. Pinned on the completion's own birth records: it
     *  returns exactly one rocket, and that rocket's class. */
    @Test
    public void oreCompletionRespawnsRocketInLaunchDim() throws Exception {
        int rid = buildAndAssembleRocket(9200);
        long mid = startOreMission(rid, 1000, 1.0f);
        MissionCompletion cargo = MissionCompletion.now(commands(), rockets, mid);
        assertEquals("the ore completion must return exactly one rocket: " + cargo.raw(),
                1, cargo.returnedRocketCount());
        assertEquals("the ore completion returns a plain rocket: " + cargo.raw(),
                "EntityRocket", cargo.returnedClass());
    }
}
