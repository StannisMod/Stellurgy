package dev.stannismod.stellurgy.test.unit;

import net.minecraft.item.ItemStack;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * What a linker remembers about the machine it is bound to, read back the way its readers read it.
 *
 * <p>A binding is a position and a dimension, written into the item. A machine that consumes a linker
 * — a railgun's destination, a guidance computer's target, a landing pad's partner — asks for both,
 * and several of them ask for the dimension without first asking whether the linker is bound at
 * all.</p>
 */
public class LinkerBindingTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** A dimension id a binding can carry — any id that is not the "unbound" answer, -1. */
    private static final int BOUND_DIMENSION = 7;

    /**
     * Resetting a linker forgets the dimension of its old binding, not only the position.
     *
     * <p>Contract: fails if {@code ItemLinker#resetPosition} stops deciding that a reset clears the
     * dimension a reader asks for. Read through {@code ItemLinker#getDimId}, the one reader every
     * consumer of a linker uses.</p>
     */
    @Pins("INV-LVP-27")
    @RedWitness(member = "ItemLinker#resetPosition", at = "setDimId(itemStack, -1);", replacedBy = "",
            fails = "a reset linker must read as unbound and answer no dimension", on = "2026-10-19")
    @Test
    public void aResetLinkerForgetsTheDimensionOfItsOldBinding() {
        ItemStack linker = new ItemStack(new ItemLinker());
        ItemLinker.setMasterCoords(linker, 1, 64, 2);
        ItemLinker.setDimId(linker, BOUND_DIMENSION);
        requireArranged("the linker must be bound before it is reset: isSet=" + ItemLinker.isSet(linker)
                        + ", dimension=" + ItemLinker.getDimId(linker),
                ItemLinker.isSet(linker) && ItemLinker.getDimId(linker) == BOUND_DIMENSION);

        ItemLinker.resetPosition(linker);

        assertTrue("a reset linker must read as unbound and answer no dimension (-1, what an unbound"
                        + " linker answers), not the one it was bound in — isSet=" + ItemLinker.isSet(linker)
                        + ", dimension=" + ItemLinker.getDimId(linker),
                !ItemLinker.isSet(linker) && ItemLinker.getDimId(linker) == -1);
    }
}
