package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ScenarioRockets;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Test;

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

    private static final String BREAKING_PROB = "breakingProb";

    /**
     * FIRST link: the volume this craft is built in is EMPTY, and a failure names what was in it.
     *
     * <p>The site stands in open air, so this ASSERTS rather than digs. It still warms the chunks -
     * the fill inside it force-loads every chunk in the box - so nothing downstream lost a
     * guarantee it had.</p>
     */

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The rocket is the one the press's own `rocket_born` record names.
        return rockets.assembleAt(site, c -> act(c).raw(), "simple", 2, 10,
                "the craft is built and worn in this volume");
    }

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }

    private double damagePartsAndReadProb(int rocketId, int iterations) throws Exception {
        String r = act("stellurgytest wear damage-parts " + rocketId + " " + iterations).raw();
        assertTrue("damage-parts must find the rocket: " + r, Reply.of(r).bool("found"));
        double prob = Reply.of("stellurgytest wear damage-parts", r).number(BREAKING_PROB);
        return prob;
    }

    /** Pins INV-RASM-07 (Wear accrual is single-gated by partsWearSystem). */
    @Pins("INV-RASM-07")
    @RedWitness(member = "StorageChunk#damageParts", at = "if (!StellurgyConfiguration.getCurrentConfig().partsWearSystem) {",
            replacedBy = "if (false) {",
            fails = "with the wear system OFF, damageParts must not advance any wear", on = "2026-10-19")
    @Test
    public void wearAccruesOnlyWhenSystemEnabled() throws Exception {
        try {
            // Make motors wear deterministically fast so the "on" case is not flaky.
            assertTrue(Reply.of(arrange("stellurgytest config set increaseWearIntensityProb 1.0").raw()).ok());

            // --- system ON: a worn motor raises the breaking probability ---
            assertTrue(Reply.of(arrange("stellurgytest config set partsWearSystem true").raw()).ok());
            int onRocket = buildAndAssemble(FixtureSite.openAir(0, 3200, 3200));
            double probOn = damagePartsAndReadProb(onRocket, 200);
            assertTrue("with the wear system ON, driving damageParts must accrue wear "
                    + "(breaking probability > 0), got " + probOn, probOn > 0);

            // --- system OFF: identical driving accrues nothing ---
            assertTrue(Reply.of(arrange("stellurgytest config set partsWearSystem false").raw()).ok());
            int offRocket = buildAndAssemble(FixtureSite.openAir(0, 3260, 3200));
            double probOff = damagePartsAndReadProb(offRocket, 200);
            assertEquals("with the wear system OFF, damageParts must not advance any wear "
                    + "stage (breaking probability stays 0)", 0.0, probOff, 1e-9);
        } finally {
            // Restore shared-harness defaults for any later test in this JVM.
            client().arrange("stellurgytest config set partsWearSystem true");
            client().arrange("stellurgytest config set increaseWearIntensityProb 0.025");
        }
    }
}
