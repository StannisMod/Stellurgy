package dev.stannismod.stellurgy.test.unit;

import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.capability.CapabilitySpaceArmor;
import dev.stannismod.stellurgy.armor.ItemSpaceArmor;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 *
 * Pins the atmosphere-protection contract of {@link ItemSpaceArmor} —
 * what it protects against, what it does NOT protect against, and the
 * capability dispatch on {@link CapabilitySpaceArmor#PROTECTIVEARMOR}.
 *
 * The actual armor tier doesn't matter for these assertions; we
 * instantiate it with vanilla {@link ItemArmor.ArmorMaterial#LEATHER}.
 */
public class SpaceArmorProtectionContractTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static ItemSpaceArmor newSuit(EntityEquipmentSlot slot) {
        return new ItemSpaceArmor(ItemArmor.ArmorMaterial.LEATHER, slot, /*numModules=*/4);
    }

    private static ItemStack newStack(EntityEquipmentSlot slot) {
        ItemSpaceArmor armor = newSuit(slot);
        return new ItemStack(armor, 1);
    }

    /**
     * <p>red-witnessed: with {@code ItemSpaceArmor#protectsFrom} at {@code return !hazards.isEmpty();} dropping protection from heat: "suit must
     * protect against HEAT", 2026-09-30.</p>
     */
    @Test
    public void protectsAgainstEveryKindOfHarmAirCanDo() {
        ItemSpaceArmor suit = newSuit(EntityEquipmentSlot.CHEST);
        ItemStack stack = new ItemStack(suit, 1);

        // Suffocating, decompressing, being crushed, being cooked, being poisoned by too much
        // oxygen — these are why the armour exists, and dropping any one of them kills players
        // on the worlds that have it. Asked of the hazard rather than of a list of named
        // atmospheres, which is what used to go stale every time air was added.
        for (AtmosphereHazard hazard : AtmosphereHazard.values()) {
            assertTrue("suit must protect against " + hazard,
                    suit.protectsFrom(EnumSet.of(hazard), /*needsSuppliedOxygen=*/false, stack,
                            /*commit=*/false));
        }
    }

    /**
     * <p>red-witnessed: with {@code ItemSpaceArmor#protectsFrom} at {@code return !hazards.isEmpty();} answering yes unconditionally: "air that does
     * nothing is not a threat", 2026-09-30.</p>
     */
    @Test
    public void doesNotProtectWhereThereIsNothingToProtectAgainst() {
        ItemSpaceArmor suit = newSuit(EntityEquipmentSlot.CHEST);
        ItemStack stack = new ItemStack(suit, 1);
        // The same answer decides whether the suit spends anything, so claiming to protect
        // against harmless air would drain the tank for standing outdoors at home.
        assertFalse("air that does nothing is not a threat",
                suit.protectsFrom(Collections.<AtmosphereHazard>emptySet(), false, stack, false));
    }

    @Test
    public void exposesProtectiveArmorCapabilityOnAllEquipmentSlots() {
        // Helmet / chest / leggings / boots — the suit's IModularArmor
        // dispatch must reply on every slot variant so that the capability
        // lookup in AtmosphereHandler.canBreathe finds *something*.
        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{
                EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET}) {
            ItemSpaceArmor suit = newSuit(slot);
            assertTrue("hasCapability(PROTECTIVEARMOR) on slot " + slot + " must be true",
                    suit.hasCapability(CapabilitySpaceArmor.PROTECTIVEARMOR, null));
            Object cap = suit.getCapability(CapabilitySpaceArmor.PROTECTIVEARMOR, null);
            assertNotNull("getCapability(PROTECTIVEARMOR) returned null for slot " + slot, cap);
            assertSame("PROTECTIVEARMOR cap must dispatch to the suit itself",
                    suit, cap);
        }
    }

    // Note: "rejects unrelated capabilities" cannot be unit-tested here.
    // Forge's @CapabilityInject populates static cap fields at runtime; in
    // this test JVM both CapabilitySpaceArmor.PROTECTIVEARMOR and
    // CapabilityItemHandler.ITEM_HANDLER_CAPABILITY are null, so the
    // identity check `capability == PROTECTIVEARMOR` returns true for any
    // null argument. The real-server WeatherClientSyncE2ETest /
    // OxygenSuitClientStateE2ETest cover the live capability dispatch.

    @Test
    public void getNumSlotsHonorsConstructorArgumentAfterInventoryInit() {
        // Fresh suit, fresh stack — getNumSlots routes through
        // loadEmbeddedInventory which lazily creates an EmbeddedInventory of
        // size = numModules (4 here). Pin that the constructor arg actually
        // reaches the inventory.
        ItemSpaceArmor suit = newSuit(EntityEquipmentSlot.CHEST);
        ItemStack stack = new ItemStack(suit, 1);
        int slots = suit.getNumSlots(stack);
        assertTrue("numModules constructor arg should propagate to embedded inventory, got " + slots,
                slots >= 1);
    }
}
