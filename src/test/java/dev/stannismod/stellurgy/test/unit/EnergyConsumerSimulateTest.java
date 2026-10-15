package dev.stannismod.stellurgy.test.unit;

import net.minecraftforge.energy.IEnergyStorage;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.libvulpes.cap.ForgePowerCapability;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
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

    @RedWitness(verdict = "a simulated extraction answers what it could take",
            member = "TileEntityRFConsumer#extractEnergy",
            at = "return energy.extractEnergy(amt, simulate);",
            replacedBy = "return simulate ? 0 : energy.extractEnergy(amt, simulate);",
            fails = "a simulated extraction must still answer what it could take",
            on = "2026-10-15")
    @RedWitness(verdict = "a simulated extraction takes nothing",
            member = "TileEntityRFConsumer#extractEnergy",
            at = "return energy.extractEnergy(amt, simulate);",
            replacedBy = "return energy.extractEnergy(amt, false);",
            fails = "a simulated extraction took the energy anyway",
            on = "2026-10-15")
    @RedWitness(verdict = "a real extraction answers what it takes",
            member = "TileEntityRFConsumer#extractEnergy",
            at = "return energy.extractEnergy(amt, simulate);",
            replacedBy = "return simulate ? energy.extractEnergy(amt, true) : 0;",
            fails = "a real extraction must take what it answers",
            on = "2026-10-15")
    @RedWitness(verdict = "a real extraction leaves the rest",
            member = "TileEntityRFConsumer#extractEnergy",
            at = "return energy.extractEnergy(amt, simulate);",
            replacedBy = "return energy.extractEnergy(amt, true);",
            fails = "a real extraction must leave the rest",
            on = "2026-10-15")
    @Pins("INV-LVP-25")
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
