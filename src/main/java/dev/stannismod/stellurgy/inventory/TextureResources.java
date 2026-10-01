package dev.stannismod.stellurgy.inventory;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import dev.stannismod.stellurgy.libvulpes.client.util.IndicatorBarImage;
import dev.stannismod.stellurgy.libvulpes.client.util.ProgressBarImage;
import dev.stannismod.stellurgy.libvulpes.util.IconResource;

public class TextureResources {
    public static final ResourceLocation progressBars = new ResourceLocation("stellurgy:textures/gui/progressBars/progressBars.png");
    public static final ResourceLocation rocketHud = new ResourceLocation("stellurgy:textures/gui/rocketHUD.png");
    public static final ResourceLocation laserGui = new ResourceLocation("stellurgy", "textures/gui/LaserTile.png");
    public static final ResourceLocation[] buttonKill = {new ResourceLocation("stellurgy", "textures/gui/buttons/kill.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/kill_hover.png"), null, null};
    public static final ResourceLocation[] buttonCopy = {new ResourceLocation("stellurgy", "textures/gui/buttons/copy.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/copy_hover.png"), null, null};
    public static final ResourceLocation[] buttonAsteroid = {new ResourceLocation("stellurgy", "textures/gui/buttons/buttonAsteroid.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/buttonAsteroid_hover.png"), null, null};
    public static final ResourceLocation[] buttonGeneric = {new ResourceLocation("stellurgy", "textures/gui/buttons/buttonGeneric.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/buttonGeneric_hover.png"), null, null};
    public static final ResourceLocation[] buttonAutoEject = {new ResourceLocation("stellurgy", "textures/gui/buttons/buttonAutoEject.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/buttonAutoEject_hover.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/buttonAutoEject_pressed.png"), null};
    public static final ResourceLocation[] tabAsteroid = {new ResourceLocation("stellurgy", "textures/gui/buttons/tabAsteroid.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/tabAsteroid_hover.png"), null, null};
    public static final ResourceLocation[] tabData = {new ResourceLocation("stellurgy", "textures/gui/buttons/tabData.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/tabData_hover.png"), null, null};
    public static final ResourceLocation[] tabWarp = {new ResourceLocation("stellurgy", "textures/gui/buttons/tabWarp.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/tabWarp_hover.png"), null, null};
    public static final ResourceLocation[] tabPlanet = {new ResourceLocation("stellurgy", "textures/gui/buttons/tabPlanet.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/tabPlanet_hover.png"), null, null};
    public static final ResourceLocation[] tabPlanetTracking = {new ResourceLocation("stellurgy", "textures/gui/buttons/tabGuidance.png"), new ResourceLocation("stellurgy", "textures/gui/buttons/tabGuidance_hover.png"), null, null};

    // for black hole color - minecraft shits itself without it
    public static final ResourceLocation locationWhitePng =    new ResourceLocation("stellurgy:textures/env/just_a_fucking_white_pixl.png");

    public static final ResourceLocation locationSunPng = new ResourceLocation("stellurgy:textures/env/sun.png");
    public static final ResourceLocation locationSunNew = new ResourceLocation("stellurgy:textures/env/sun2.png");
    public static final ResourceLocation locationAccretionDisk = new ResourceLocation("stellurgy:textures/env/accretiondisk.png");
    public static final ResourceLocation locationAccretionDiskDense = new ResourceLocation("stellurgy:textures/env/accretiondiskdense.png");
    public static final ResourceLocation locationBlackHole = new ResourceLocation("stellurgy:textures/env/blackhole.png");
    public static final ResourceLocation locationBlackHole_icon = new ResourceLocation("stellurgy:textures/env/blackhole_icon.png");
    public static final ResourceLocation locationReticle = new ResourceLocation("stellurgy:textures/gui/recticle.png");
    public static final ResourceLocation selectionCircle = new ResourceLocation("stellurgy:textures/gui/Selection.png");
    public static final ResourceLocation planetSelectorBar = new ResourceLocation("stellurgy:textures/gui/progressBars/PlanetSelectorBars.png");
    public static final ResourceLocation verticalBar = new ResourceLocation("stellurgy:textures/gui/BorderVertical.png");
    public static final ResourceLocation horizontalBar = new ResourceLocation("stellurgy:textures/gui/BorderHorizontal.png");
    public static final ResourceLocation jetpackIconEnabled = new ResourceLocation("stellurgy:textures/gui/jetpack.png");
    public static final ResourceLocation jetpackIconDisabled = new ResourceLocation("stellurgy:textures/gui/jetpackDisabled.png");
    public static final ResourceLocation jetpackIconHover = new ResourceLocation("stellurgy:textures/gui/jetpackHover.png");
    public static final ResourceLocation modularHelm = new ResourceLocation("stellurgy:textures/gui/space_helmet.png");
    public static final ResourceLocation modularChest = new ResourceLocation("stellurgy:textures/gui/space_chestplate.png");
    public static final ResourceLocation modularLegs = new ResourceLocation("stellurgy:textures/gui/space_leggings.png");
    public static final ResourceLocation modularBoots = new ResourceLocation("stellurgy:textures/gui/space_boots.png");
    public static final ResourceLocation frameHUDBG = new ResourceLocation("stellurgy:textures/gui/FrameBG.png");
    public static final ResourceLocation[] armorSlots = new ResourceLocation[]{modularHelm, modularChest, modularLegs, modularBoots};
    public static final ResourceLocation earthCandy = new ResourceLocation("stellurgy:textures/gui/eyeCandy/Earth.png");
    public static final ResourceLocation metalPlate = new ResourceLocation("stellurgy:textures/models/metalPlate.png");
    public static final ResourceLocation diamondMetal = new ResourceLocation("stellurgy:textures/models/diamondMetal.png");
    public static final ResourceLocation fan = new ResourceLocation("stellurgy:textures/models/fan.png");
    public static final ResourceLocation genericStation = new ResourceLocation("stellurgy:textures/gui/genericStation.png");

    public static final IconResource ioSlot = new IconResource(212, 0, 18, 18, null);
    public static final IconResource idChip = new IconResource(230, 0, 18, 18, null);
    public static final IconResource slotO2 = new IconResource(238, 238, 18, 18, progressBars);
    public static final IconResource slotSatellite = new IconResource(220, 238, 18, 18, progressBars);

    public static final IconResource functionComponent = new IconResource(212, 18, 18, 18, null);
    public static final IconResource powercomponent = new IconResource(230, 18, 18, 18, null);
    public static final IconResource laserGuiBG = new IconResource(8, 16, 65, 70, laserGui);
    public static final IconResource earthCandyIcon = new IconResource(0, 0, 128, 128, earthCandy);

    public static final ProgressBarImage doubleWarningSideBarIndicator = new IndicatorBarImage(0, 84, 142, 16, 0, 100, 2, 9, 2, 2, EnumFacing.EAST, planetSelectorBar);
    public static final ProgressBarImage doubleWarningSideBar = new ProgressBarImage(0, 59, 142, 16, 0, 75, 136, 9, 2, 2, EnumFacing.EAST, planetSelectorBar);
    public static final ProgressBarImage massIndicator = new ProgressBarImage(0, 0, 81, 23, 6, 23, 75, 9, 2, 9, EnumFacing.EAST, planetSelectorBar);
    public static final ProgressBarImage atmIndicator = new ProgressBarImage(0, 0, 81, 23, 6, 32, 75, 9, 2, 9, EnumFacing.EAST, planetSelectorBar);
    public static final ProgressBarImage distanceIndicator = new ProgressBarImage(0, 0, 81, 23, 6, 41, 75, 9, 2, 9, EnumFacing.EAST, planetSelectorBar);
    public static final ProgressBarImage genericSlider = new ProgressBarImage(0, 0, 81, 23, 6, 41, 75, 9, 2, 9, EnumFacing.EAST, planetSelectorBar);
    public static final ProgressBarImage progressScience = new ProgressBarImage(185, 0, 16, 24, 201, 0, 16, 24, 0, 0, EnumFacing.UP, TextureResources.progressBars);

    public static final ProgressBarImage progressToMission = new ProgressBarImage(25, 248, 112, 8, 25, 240, 112, 8, EnumFacing.EAST, TextureResources.progressBars);
    public static final ProgressBarImage progressFromMission = new ProgressBarImage(25, 232, 112, 8, 25, 224, 112, 8, EnumFacing.WEST, TextureResources.progressBars);
    public static final ProgressBarImage workMission = new ProgressBarImage(25, 216, 112, 8, 25, 208, 112, 8, EnumFacing.EAST, TextureResources.progressBars);


    public static final ProgressBarImage crystallizerProgressBar = new ProgressBarImage(0, 0, 31, 66, 31, 0, 23, 49, 4, 17, EnumFacing.UP, TextureResources.progressBars);
    public static final ProgressBarImage cuttingMachineProgressBar = new ProgressBarImage(54, 0, 42, 42, 96, 0, 36, 36, 3, 3, EnumFacing.EAST, TextureResources.progressBars);
    public static final ProgressBarImage arcFurnaceProgressBar = new ProgressBarImage(0, 66, 42, 42, 42, 66, 42, 42, 0, 0, EnumFacing.UP, TextureResources.progressBars);
    public static final ProgressBarImage smallPlatePresser = new ProgressBarImage(0, 108, 16, 48, 0, 0, 1, 1, EnumFacing.DOWN, TextureResources.progressBars); //TODO
    public static final ProgressBarImage latheProgressBar = new ProgressBarImage(185, 24, 23, 4, 185, 28, 23, 4, EnumFacing.EAST, TextureResources.progressBars);
    public static final ProgressBarImage rollingMachineProgressBar = new ProgressBarImage(84, 66, 41, 32, 125, 66, 41, 32, EnumFacing.EAST, TextureResources.progressBars);
    public static final ProgressBarImage terraformProgressBar = new ProgressBarImage(16, 109, 106, 30, 16, 138, 106, 30, EnumFacing.EAST, TextureResources.progressBars);
}
