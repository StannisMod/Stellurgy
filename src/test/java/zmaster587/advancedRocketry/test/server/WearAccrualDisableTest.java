package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for the parts-wear system.
 *
 * <p>Wear ACCRUAL goes through {@code StorageChunk.damageParts()}. With
 * {@code partsWearSystem} off, no part may advance a wear stage, so a rocket
 * driven through that entry point any number of times keeps a zero breaking
 * probability; with the system on, its motors wear and the breaking probability
 * rises. This pins the player-facing promise that turning the wear system off in
 * the config stops parts wearing at all (the consequences — thrust loss, tank
 * leak, seat block — are already gated and covered by {@code WearSystemTest}).</p>
 */
public class WearAccrualDisableTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String BREAKING_PROB = "breakingProb";

    private String cmd(String c) throws Exception {
        return String.join("\n", client().execute(c));
    }

    /**
     * FIRST link: the volume this craft is built in is EMPTY, and a failure names what was in it.
     *
     * <p>The site stands in open air, so this ASSERTS rather than digs. It still warms the chunks -
     * the fill inside it force-loads every chunk in the box - so nothing downstream lost a
     * guarantee it had.</p>
     */

    private int buildAndAssemble(FixtureSite site) throws Exception {
        String assemble = RocketFixture.assembleAt(site, this::cmd, "simple", 2, 10,
                "the craft is built and worn in this volume");
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());
        String list = cmd("artest rocket list 0");
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket id after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    private double damagePartsAndReadProb(int rocketId, int iterations) throws Exception {
        String r = cmd("artest wear damage-parts " + rocketId + " " + iterations);
        assertTrue("damage-parts must find the rocket: " + r, Reply.of(r).bool("found"));
        double prob = Reply.of("artest wear damage-parts", r).number(BREAKING_PROB);
        return prob;
    }

    @Test
    public void wearAccruesOnlyWhenSystemEnabled() throws Exception {
        try {
            // Make motors wear deterministically fast so the "on" case is not flaky.
            assertTrue(Reply.of(cmd("artest config set increaseWearIntensityProb 1.0")).ok());

            // --- system ON: a worn motor raises the breaking probability ---
            assertTrue(Reply.of(cmd("artest config set partsWearSystem true")).ok());
            int onRocket = buildAndAssemble(FixtureSite.openAir(0, 3200, 3200));
            double probOn = damagePartsAndReadProb(onRocket, 200);
            assertTrue("with the wear system ON, driving damageParts must accrue wear "
                    + "(breaking probability > 0), got " + probOn, probOn > 0);

            // --- system OFF: identical driving accrues nothing ---
            assertTrue(Reply.of(cmd("artest config set partsWearSystem false")).ok());
            int offRocket = buildAndAssemble(FixtureSite.openAir(0, 3260, 3200));
            double probOff = damagePartsAndReadProb(offRocket, 200);
            assertEquals("with the wear system OFF, damageParts must not advance any wear "
                    + "stage (breaking probability stays 0)", 0.0, probOff, 1e-9);
        } finally {
            // Restore shared-harness defaults for any later test in this JVM.
            client().execute("artest config set partsWearSystem true");
            client().execute("artest config set increaseWearIntensityProb 0.025");
        }
    }
}
