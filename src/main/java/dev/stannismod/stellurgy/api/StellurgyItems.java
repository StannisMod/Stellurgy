package dev.stannismod.stellurgy.api;

import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor.ArmorMaterial;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.common.util.EnumHelper;

/**
 * Stores references to Stellurgy's items
 */
public class StellurgyItems {

    //TODO: fix
    /** Effectively final, process lifetime: built once at class initialisation. */
    public static final ArmorMaterial spaceSuit = EnumHelper.addArmorMaterial("spaceSuit", "", ArmorMaterial.DIAMOND.getDurability(EntityEquipmentSlot.CHEST), new int[]{1, 1, 1, 1}, 0, new SoundEvent(new ResourceLocation("")), 0);

    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemWafer;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemCircuitPlate;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemIC;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSatellitePowerSource;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSatellitePrimaryFunction;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemOreScanner;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemQuartzCrucible;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemDataUnit;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemMemoryCrystal;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemRepairWelder;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSatellite;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSatelliteIdChip;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemPlanetIdChip;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemMisc;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSawBlade;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSpaceStationChip;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSpaceStation;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSpaceSuit_Helmet;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSpaceSuit_Chest;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSpaceSuit_Leggings;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSpaceSuit_Boots;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSmallAirlockDoor;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemCarbonScrubberCartridge;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSealDetector;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemJackhammer;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemAsteroidChip;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemLens;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemJetpack;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemPressureTank;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemUpgrade;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemAtmAnalyser;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemBiomeChanger;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemWeatherController;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemBasicLaserGun;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemSpaceElevatorChip;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemBeaconFinder;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemThermite;
    /** Effectively final, process lifetime: written only by Stellurgy.registerItems. */
    public static Item itemHovercraft;
}
