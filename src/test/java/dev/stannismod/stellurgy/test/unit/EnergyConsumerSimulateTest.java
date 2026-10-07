package dev.stannismod.stellurgy.test.unit;

import net.minecraftforge.energy.IEnergyStorage;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.libvulpes.cap.ForgePowerCapability;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.tile.TilePump;

import static org.junit.Assert.assertEquals;

/**
 * A machine asked how much energy could be taken from it answers without giving any up.
 *
 * <p>Asked through the Forge energy capability a machine hands out — the door a cable or another
 * mod uses — on a real machine (a pump, one of the {@code TileEntityRFConsumer}s), with its buffer
 * set directly so the arithmetic is the only thing under test. Not seen: what any particular cable
 * does with the answer.</p>
 */
public class EnergyConsumerSimulateTest {

    /** The pump's buffer for the test; any amount above {@link #ASKED} does. */
    private static final int STORED = 1000;
    /** What is asked for: less than is stored, so the answer is the request itself. */
    private static final int ASKED = 400;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * red-witnessed: with {@code TileEntityRFConsumer#extractEnergy} at
     * {@code return energy.extractEnergy(amt, simulate);} passing {@code false} again (the shape it
     * shipped with), this fails at "a simulated extraction took the energy anyway
     * expected:<1000> but was:<600>" (2026-10-06).
     */
    @Test
    public void aSimulatedExtractionLeavesTheEnergyWhereItWas() {
        TilePump pump = new TilePump();
        pump.setEnergyStored(STORED);
        IEnergyStorage energy = new ForgePowerCapability(pump);

        assertEquals("a simulated extraction must still answer what it could take",
                ASKED, energy.extractEnergy(ASKED, true));
        assertEquals("a simulated extraction took the energy anyway",
                STORED, pump.getUniversalEnergyStored());
        assertEquals("a real extraction must take what it answers",
                ASKED, energy.extractEnergy(ASKED, false));
        assertEquals("a real extraction must leave the rest",
                STORED - ASKED, pump.getUniversalEnergyStored());
    }
}
