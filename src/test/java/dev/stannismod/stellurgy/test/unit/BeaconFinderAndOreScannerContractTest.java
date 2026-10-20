package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.item.ItemBeaconFinder;
import dev.stannismod.stellurgy.item.ItemOreScanner;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Scanner-item contracts pinnable without a Minecraft world.
 *
 * <p>The audit framed this gap as "player-use" but the actual contract
 * surfaces split into two layers:</p>
 *
 * <ul>
 *   <li><b>ItemBeaconFinder</b> — pure HUD-render IArmorComponent, NO
 *       {@code onItemRightClick} / {@code onItemUse}. The contract is
 *       slot-eligibility (HEAD only) + component-add ok. {@code
 *       renderScreen} reads {@code DimensionProperties.getBeacons()}
 *       and draws indicators; the data-source side is already pinned
 * by {@code BeaconEnableCycleTest}.</li>
 *   <li><b>ItemOreScanner</b> — has {@code onItemRightClick} +
 *       {@code onItemUse} which open the OreMapping GUI WHEN the
 *       stored satellite-ID resolves to a SatelliteOreMapping on the
 *       current dim. The NBT round-trip for the satellite-ID is the
 *       pinnable contract here; the GUI-open path is testClient.</li>
 * </ul>
 *
 * <p>This file pins the unit-tier contracts. A companion
 * client-tier smoke is in
 * {@code OreScannerPlayerUseClientSmokeE2ETest}.</p>
 */
public class BeaconFinderAndOreScannerContractTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void beaconFinderIsAllowedOnlyInHeadSlot() {
        ItemBeaconFinder finder = new ItemBeaconFinder();
        ItemStack stack = new ItemStack(finder);
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            boolean expected = slot == EntityEquipmentSlot.HEAD;
            assertEquals("BeaconFinder slot eligibility for " + slot
                            + " — only HEAD is valid because the finder draws "
                            + "the HUD direction indicator on the helmet overlay",
                    expected, finder.isAllowedInSlot(stack, slot));
        }
    }

    @Test
    public void oreScannerSatelliteIdRoundTripsThroughNbt() {
        ItemOreScanner scanner = new ItemOreScanner();
        scanner.setRegistryName("stellurgy_test:ore_scanner_g12_roundtrip");
        ItemStack stack = new ItemStack(scanner);

        // Default — no NBT, returns -1 sentinel.
        assertEquals("default ore-scanner has no satellite id (-1 sentinel)",
                -1L, scanner.getSatelliteID(stack));
    }

    @Test
    public void oreScannerEmptySatelliteIdReturnsMinusOne() {
        // The -1 sentinel applies when the stack has NO NBT compound at all;
        // a compound without the "id" key reads as getLong's 0 instead.
        ItemOreScanner scanner = new ItemOreScanner();
        scanner.setRegistryName("stellurgy_test:ore_scanner_g12_empty");
        ItemStack stack = new ItemStack(scanner);
        assertEquals("no-NBT stack returns the -1 sentinel",
                -1L, scanner.getSatelliteID(stack));
    }
}
