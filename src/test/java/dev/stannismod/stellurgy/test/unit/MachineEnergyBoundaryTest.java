package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import dev.stannismod.stellurgy.libvulpes.cap.ForgePowerCapability;
import dev.stannismod.stellurgy.libvulpes.tile.energy.TileForgePowerInput;
import dev.stannismod.stellurgy.libvulpes.tile.energy.TileForgePowerOutput;
import dev.stannismod.stellurgy.libvulpes.util.MultiBattery;
import dev.stannismod.stellurgy.tile.TilePump;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where energy may cross the edge of a machine-library store, and how much of it.
 *
 * <p>Three stores, three directions. A multiblock draws its running cost from its power plugs as ONE
 * aggregate; a plug is an input or an output and the world may move energy through it only that way;
 * a single-block consumer takes energy and gives none back. Every subject here is the production
 * class itself, built bare: none of the three decisions reads a world, so a tile with none is the
 * whole of what decides.</p>
 *
 * <p>What this does not see: whether a placed block hands the world THIS capability object (that is
 * {@code getCapability}, which only a running game can ask with a real capability key), and the
 * Tesla adapter, which forwards to the store without consulting it.</p>
 */
public class MachineEnergyBoundaryTest {

    /** What each plug is charged with — an arrangement amount, under a plug's capacity. */
    private static final int CHARGE = 1000;

    /** What a machine asks its plugs for in one tick — any amount below one plug's charge. */
    private static final int REQUEST = 300;

    private static TileForgePowerInput chargedInputPlug() {
        TileForgePowerInput plug = new TileForgePowerInput();
        requireArranged("a bare input plug must hold " + CHARGE + " FE; its capacity is "
                        + plug.getMaxEnergyStored(),
                plug.acceptEnergy(CHARGE, false) == CHARGE && plug.getUniversalEnergyStored() == CHARGE);
        return plug;
    }

    /**
     * A machine with two charged power plugs pays a request once, in total.
     *
     * <p>Contract: fails if {@code MultiBattery#extractEnergy} stops deciding that a request is taken
     * once across all its batteries — the remainder asked of the next, nothing asked once it is met.
     * The aggregate is the machine's whole view of its power, so an over-draw here is a running cost
     * multiplied by the number of plugs.</p>
     * <p>red-witnessed: with {@code MultiBattery#extractEnergy} at {@code amtExtracted += battery.extractEnergy(amt - amtExtracted, simulate);} asking every battery for {@code amt}, the stop removed, fails: "the aggregate must pay the request once, not once per plug — paid 600, the plugs hold 700 and 700 of 1000 each" (2026-10-07).</p>
     */
    @Test
    public void aMachineWithTwoPowerPlugsPaysItsRequestOnceInTotal() {
        TileForgePowerInput first = chargedInputPlug();
        TileForgePowerInput second = chargedInputPlug();
        MultiBattery aggregate = new MultiBattery();
        aggregate.addBattery(first);
        aggregate.addBattery(second);

        int heldBefore = first.getUniversalEnergyStored() + second.getUniversalEnergyStored();
        int paid = aggregate.extractEnergy(REQUEST, false);
        int left = first.getUniversalEnergyStored() + second.getUniversalEnergyStored();

        assertTrue("the aggregate must pay the request once, not once per plug — paid " + paid
                        + ", the plugs hold " + first.getUniversalEnergyStored() + " and "
                        + second.getUniversalEnergyStored() + " of " + CHARGE + " each",
                paid == REQUEST && left == heldBefore - REQUEST);
    }

    /**
     * An aggregate with no batteries takes a maximum without dividing by zero.
     *
     * <p>Contract: fails if {@code MultiBattery#setMaxEnergyStored} stops deciding that a maximum
     * shared among no batteries sets nothing. A controller's aggregate is empty whenever its structure
     * is not formed.</p>
     * <p>red-witnessed: with {@code MultiBattery#setMaxEnergyStored} at {@code if(batteries.isEmpty())} removed, fails: "java.lang.ArithmeticException: / by zero" (2026-10-07).</p>
     */
    @Test
    public void anEmptyAggregateTakesAMaximumWithoutThrowing() {
        MultiBattery aggregate = new MultiBattery();

        aggregate.setMaxEnergyStored(CHARGE);

        assertEquals("an aggregate of no batteries holds no capacity", 0, aggregate.getMaxEnergyStored());
    }

    /**
     * An input plug takes energy from the world and gives none back to it.
     *
     * <p>Contract: fails if {@code ForgePowerCapability#extractEnergy} stops deciding to refuse a
     * store that declares it cannot be extracted from. The plug's own store is what the machine draws
     * from, so the refusal has to live at the world's edge, not in the store.</p>
     * <p>red-witnessed: with {@code ForgePowerCapability#extractEnergy} at {@code if(!energy.canExtract())} removed, fails: "nothing may be drained out of an input plug — drained 300, held 1300 before and 1000 after" (2026-10-07).</p>
     */
    @Test
    public void anInputPlugCannotBeDrainedThroughForgeEnergy() {
        TileForgePowerInput plug = chargedInputPlug();
        ForgePowerCapability edge = new ForgePowerCapability(plug);

        assertEquals("the input plug must take energy through the same capability", REQUEST,
                edge.receiveEnergy(REQUEST, false));
        int heldBefore = plug.getUniversalEnergyStored();

        int drained = edge.extractEnergy(REQUEST, false);

        assertTrue("nothing may be drained out of an input plug — drained " + drained + ", held "
                        + heldBefore + " before and " + plug.getUniversalEnergyStored() + " after",
                drained == 0 && plug.getUniversalEnergyStored() == heldBefore);
    }

    /**
     * An output plug gives energy to the world and takes none from it.
     *
     * <p>Contract: fails if {@code ForgePowerCapability#receiveEnergy} stops deciding to refuse a
     * store that declares it cannot receive. A generator fills its output plug through the store
     * itself, so the store cannot refuse and the edge must.</p>
     * <p>red-witnessed: with {@code ForgePowerCapability#receiveEnergy} at {@code if(!energy.canReceive())} removed, fails: "nothing may be pushed into an output plug — took 300, held 700 before and 1000 after" (2026-10-07).</p>
     */
    @Test
    public void anOutputPlugCannotBeFilledThroughForgeEnergy() {
        TileForgePowerOutput plug = new TileForgePowerOutput();
        requireArranged("a bare output plug must hold " + CHARGE + " FE from its owner; its capacity is "
                        + plug.getMaxEnergyStored(),
                plug.acceptEnergy(CHARGE, false) == CHARGE);
        ForgePowerCapability edge = new ForgePowerCapability(plug);

        assertEquals("the output plug must give energy through the same capability", REQUEST,
                edge.extractEnergy(REQUEST, false));
        int heldBefore = plug.getUniversalEnergyStored();

        int pushed = edge.receiveEnergy(REQUEST, false);

        assertTrue("nothing may be pushed into an output plug — took " + pushed + ", held "
                        + heldBefore + " before and " + plug.getUniversalEnergyStored() + " after",
                pushed == 0 && plug.getUniversalEnergyStored() == heldBefore);
    }

    /**
     * A single-block consumer takes energy and cannot be drained, through Forge Energy or through its
     * own store interface.
     *
     * <p>Contract: fails if {@code TileEntityRFConsumer#extractEnergy} stops deciding that a consumer
     * gives nothing to an outside caller, or {@code ForgePowerCapability#extractEnergy} stops refusing
     * it at the edge. The pump stands for every consumer: the decision is the base class's and the
     * pump does not override it.</p>
     * <p>red-witnessed: with {@code TileEntityRFConsumer#extractEnergy} at {@code if(!canExtract())} removed, fails: "the consumer's own store interface must give nothing to an outside caller — simulated 300, taken 300, left 700 of 1000" (2026-10-07).</p>
     */
    @Test
    public void aConsumerCannotBeDrainedByAnOutsideCaller() {
        TilePump pump = new TilePump();
        ForgePowerCapability edge = new ForgePowerCapability(pump);

        assertEquals("the consumer must take energy through Forge Energy", CHARGE,
                edge.receiveEnergy(CHARGE, false));
        requireArranged("the pump must hold what it took", pump.getUniversalEnergyStored() == CHARGE);

        assertEquals("Forge Energy must drain nothing from a consumer", 0, edge.extractEnergy(REQUEST, false));

        int simulated = pump.extractEnergy(REQUEST, true);
        int taken = pump.extractEnergy(REQUEST, false);
        assertTrue("the consumer's own store interface must give nothing to an outside caller — simulated "
                        + simulated + ", taken " + taken + ", left " + pump.getUniversalEnergyStored() + " of "
                        + CHARGE,
                simulated == 0 && taken == 0 && pump.getUniversalEnergyStored() == CHARGE);
    }
}
