package dev.stannismod.stellurgy.test.unit;

import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.armor.ItemSpaceArmor;
import dev.stannismod.stellurgy.armor.ItemSpaceChest;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ItemSpaceArmor / ItemSpaceChest unit-tier contracts.
 *
 * <p>Pins pure-function behaviour that does NOT require a real
 * {@link net.minecraft.entity.player.EntityPlayer} or a registered
 * {@link dev.stannismod.stellurgy.libvulpes.api.IArmorComponent}. Component install /
 * tick paths are covered at server tier via the suit-workstation +
 * tile-init-modules probe.</p>
 *
 * <p>Coverage scope:</p>
 * <ol>
 *   <li>{@link ItemSpaceArmor#protectsFrom} matrix — the suit answers for
 *       every hazard the model can raise, and for nothing where there is
 *       no hazard. Swept over the enum rather than over a written-out
 *       list, so a hazard added later is covered the day it exists.</li>
 *   <li>Empty-stack contracts for {@code getNumSlots},
 *       {@code getComponents}, {@code getComponentInSlot} — a freshly
 *       crafted suit has no NBT compound; production callers rely on
 *       these returning zero / empty / EMPTY without NPE.</li>
 *   <li>{@code getColor} default — no NBT compound must yield the
 *       white sentinel 0xFFFFFF (used by the renderer / dyeing path).</li>
 *   <li>{@link ItemSpaceChest#getAirRemaining} contract — no fluid
 *       components &rarr; 0 air, never negative.</li>
 * </ol>
 */
public class SpaceArmorContractTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** A test-only ItemSpaceArmor instance — production passes the
     *  appropriate ArmorMaterial / slot / numModules in
     *  StellurgyItems static init. Here we use IRON + CHEST + 6
     *  module slots as a representative chest-tier shape. The constructor
     *  doesn't touch any registry beyond vanilla Bootstrap, so it's safe
     *  in unit-tier. */
    private static ItemSpaceArmor chest() {
        return new ItemSpaceArmor(ItemArmor.ArmorMaterial.IRON,
                EntityEquipmentSlot.CHEST, 6);
    }

    private static ItemSpaceChest chestWithFluid() {
        return new ItemSpaceChest(ItemArmor.ArmorMaterial.IRON,
                EntityEquipmentSlot.CHEST, 6);
    }

    /** Stack carrying the armor item — production checks Item identity
     *  in some textures paths, but for the pure-NBT contracts here any
     *  stack with the armor item is enough. */
    private static ItemStack stackOf(ItemSpaceArmor armor) {
        return new ItemStack(armor, 1);
    }

    // ───────────────────── the protection matrix ─────────────────────────

    @Test
    public void protectsFromEveryHazardTheModelCanRaise() {
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        // Swept over the enum, not over a list somebody has to remember to extend: a suit that
        // stopped being proof against one kind of harm would be a design decision, and this is
        // where it has to be made deliberately rather than by omission.
        for (AtmosphereHazard hazard : AtmosphereHazard.values()) {
            assertTrue("space armor must protect from " + hazard,
                    armor.protectsFrom(EnumSet.of(hazard), /*needsSuppliedOxygen=*/false, stack,
                            /*commit=*/false));
        }
    }

    @Test
    public void protectsFromNothingWhereThereIsNoHazard() {
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        // Being asked about air that is doing nothing must answer no — the answer is also what
        // decides whether wearing the suit costs anything, so a yes here would spend the tank
        // for standing in a meadow.
        assertFalse("air that raises no hazard needs no protecting from",
                armor.protectsFrom(Collections.<AtmosphereHazard>emptySet(), false, stack, false));
    }

    @Test
    public void theProtectionDecisionIsTheSameWhetherOrNotItIsActedOn() {
        // Asking must not change the answer: the commit flag exists so that speculative callers
        // do not spend anything, and a piece whose VERDICT moved with it would make every such
        // caller a liar. ItemSpaceArmor consumes nothing, so both readings must agree.
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        for (Set<AtmosphereHazard> hazards : java.util.Arrays.asList(
                EnumSet.of(AtmosphereHazard.DECOMPRESSION),
                EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.HEAT),
                Collections.<AtmosphereHazard>emptySet())) {
            boolean withCommit = armor.protectsFrom(hazards, false, stack, true);
            boolean withoutCommit = armor.protectsFrom(hazards, false, stack, false);
            assertEquals("protect decision must be commit-invariant for " + hazards,
                    withCommit, withoutCommit);
        }
    }

    // ───────────────────── Empty-stack contracts ─────────────────────────

    @Test
    public void emptyStackReportsConfiguredNumSlots() {
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        assertFalse("precondition: empty stack has no NBT", stack.hasTagCompound());
        // No NBT -> loadEmbeddedInventory builds a fresh inv of size numModules.
        assertEquals("empty stack must report configured numModules (6)",
                6, armor.getNumSlots(stack));
    }

    @Test
    public void emptyStackHasNoComponents() {
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        assertNotNull("getComponents must not return null", armor.getComponents(stack));
        assertTrue("empty stack must have empty components list",
                armor.getComponents(stack).isEmpty());
    }

    @Test
    public void getComponentInSlotOnEmptyStackReturnsEmpty() {
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        for (int i = 0; i < 6; i++) {
            ItemStack inSlot = armor.getComponentInSlot(stack, i);
            assertNotNull("getComponentInSlot must not return null at slot " + i, inSlot);
            assertTrue("empty stack must report ItemStack.EMPTY at slot " + i,
                    inSlot.isEmpty());
        }
    }

    @Test
    public void getColorDefaultsToWhiteWithoutDisplayTag() {
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        // Default white sentinel — production renderer multiplies the
        // texture by this. A regression that returns 0 would render the
        // suit black until a dye is applied.
        assertEquals(0xFFFFFF, armor.getColor(stack));
    }

    @Test
    public void getColorReadsDisplayTagColorWhenPresent() {
        ItemSpaceArmor armor = chest();
        ItemStack stack = stackOf(armor);
        NBTTagCompound nbt = new NBTTagCompound();
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", 0xFF8800); // amber
        nbt.setTag("display", display);
        stack.setTagCompound(nbt);
        assertEquals(0xFF8800, armor.getColor(stack));
    }

    // ───────────────────── ItemSpaceChest specifics ──────────────────────

    @Test
    public void chestAirRemainingOnEmptyStackIsZero() {
        ItemSpaceChest chest = chestWithFluid();
        ItemStack stack = new ItemStack(chest, 1);
        // No components -> no oxygen-containing pressure tank -> 0 air.
        // Crucially: must not throw on the "no NBT, no components" path.
        assertEquals(0, chest.getAirRemaining(stack));
    }

    @Test
    public void chestSlotsAtIndexTwoAndAboveAcceptAnyItem() {
        // Production contract (isItemValidForSlot, lines 29-36):
        //   slot >= 2 -> return true unconditionally.
        //   slot 0/1 -> only oxygen-fluid items.
        // The "unconditional true for slot >= 2" branch is the surface
        // GUI-side player relies on for inserting modules into the chest's
        // generic module slots. A regression here makes those slots refuse
        // every item.
        ItemSpaceChest chest = chestWithFluid();
        ItemStack anyItem = new ItemStack(Items.STICK, 1);
        for (int slot = 2; slot < 6; slot++) {
            assertTrue("chest must accept any item at module slot " + slot,
                    chest.isItemValidForSlot(anyItem, slot));
        }
    }

    @Test
    public void chestExternalModifySlotZeroAndOneRejectedSlotsAboveAccepted() {
        // canBeExternallyModified contract (lines 39-41): hopper / shulker
        // / external automation may only push items into the module slots
        // (>= 2). Slots 0/1 are oxygen tanks, modified exclusively through
        // the suit's GUI fluid-handler path.
        ItemSpaceChest chest = chestWithFluid();
        ItemStack stack = new ItemStack(chest, 1);
        assertFalse("slot 0 (oxygen tank) must reject external modification",
                chest.canBeExternallyModified(stack, 0));
        assertFalse("slot 1 (oxygen tank) must reject external modification",
                chest.canBeExternallyModified(stack, 1));
        for (int slot = 2; slot < 6; slot++) {
            assertTrue("slot " + slot + " (module) must accept external modification",
                    chest.canBeExternallyModified(stack, slot));
        }
    }
}
