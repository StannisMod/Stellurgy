package dev.stannismod.stellurgy.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.subsystem.heat.WasteHeat;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The supply side of the ship thermal system: work leaves heat behind, and a loop can come for it.
 *
 * <p>What these pin is that spending energy PRODUCES something collectable. The whole subsystem —
 * pipes, radiators, chiller, dump, melting hull, the drive's refusal — is machinery for moving heat,
 * and until this component existed the only thing on a ship that made any was a single life-support
 * machine. Every heat test injected a charge directly, so nothing in the tier could fail on it.
 */
public class WasteHeatTest {

    /** Pinned here so no assertion depends on the shipped default moving. */
    private static final int WASTE_FRACTION = 300;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private boolean prevShipHeat;
    private int prevFraction;

    @Before
    public void armTheThermalSystem() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        prevShipHeat = config.shipHeat;
        prevFraction = config.shipHeatWasteFraction;
        config.shipHeat = true;
        config.shipHeatWasteFraction = WASTE_FRACTION;
    }

    @After
    public void restoreConfig() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeat = prevShipHeat;
        config.shipHeatWasteFraction = prevFraction;
    }

    private static int fraction() {
        return StellurgyConfiguration.getCurrentConfig().shipHeatWasteFraction;
    }

    @Test
    public void spendingEnergyLeavesHeatForALoopToCollect() {
        WasteHeat waste = new WasteHeat();
        waste.spend(10_000);

        assertTrue("a machine that spent energy must offer a loop something to pick up",
                waste.getPendingHeat() > 0);
        assertEquals("and it is the configured share of what was actually spent",
                10_000L * fraction() / 1000L, waste.getPendingHeat());
    }

    /** Half the work, half the heat — the same relation the power cost already has. */
    @Test
    public void aMachineRunningSlowerHeatsAShipProportionallyLess() {
        WasteHeat busy = new WasteHeat();
        WasteHeat idle = new WasteHeat();

        busy.spend(10_000);
        idle.spend(1_000);

        assertEquals("a tenth of the work is a tenth of the heat",
                busy.getPendingHeat() / 10, idle.getPendingHeat(), 1.0D);
    }

    @Test
    public void whatALoopTakesIsGoneFromTheMachine() {
        WasteHeat waste = new WasteHeat();
        waste.spend(10_000);
        int offered = waste.getPendingHeat();

        int taken = waste.takeHeat(offered / 2);

        assertEquals("a loop gets what it asked for", offered / 2, taken);
        assertEquals("and the machine no longer holds it", offered - taken, waste.getPendingHeat());
        assertEquals("a second taker cannot have the same heat twice",
                waste.getPendingHeat(), waste.takeHeat(Integer.MAX_VALUE));
        assertEquals("and the buffer is empty afterwards", 0, waste.getPendingHeat());
    }

    /**
     * Heat nobody collects went into the air around the machine. The buffer is shallow on purpose,
     * which is why a planetside base with no coolant loop needs no thermal build at all.
     */
    @Test
    public void uncollectedHeatDoesNotAccumulateWithoutBound() {
        WasteHeat waste = new WasteHeat();
        for (int tick = 0; tick < 2_000; tick++) {
            waste.spend(1_000);
        }
        long perTick = 1_000L * fraction() / 1000L;

        assertTrue("two thousand ticks of unclaimed production must not be two thousand ticks' worth: "
                        + waste.getPendingHeat(),
                waste.getPendingHeat() <= perTick * 20L);
    }

    /** The subsystem's off switch reaches its supply side too, or the gate does not fully disable. */
    @Test
    public void withTheThermalSystemOffNothingIsProducedAtAll() {
        StellurgyConfiguration.getCurrentConfig().shipHeat = false;
        WasteHeat waste = new WasteHeat();

        waste.spend(1_000_000);

        assertEquals("a disabled mechanic produces nothing for anyone to collect",
                0, waste.getPendingHeat());
    }
}
