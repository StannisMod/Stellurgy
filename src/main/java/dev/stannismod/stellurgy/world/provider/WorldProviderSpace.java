package dev.stannismod.stellurgy.world.provider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBiomes;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.client.render.planet.RenderSpaceSky;
import dev.stannismod.stellurgy.client.render.planet.RenderSpaceTravelSky;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import dev.stannismod.stellurgy.world.ChunkProviderSpace;

import javax.annotation.Nullable;

public class WorldProviderSpace extends WorldProviderPlanet {
    private IRenderHandler skyRender;

    @Override
    public double getHorizon() {
        return 0;
    }

    //TODO: figure out celestial angle from coords

    @Override
    public boolean isPlanet() {
        return false;
    }


    public int getAverageGroundLevel() {
        return 0;
    }

    @Override
    public IChunkGenerator createChunkGenerator() {
        return new ChunkProviderSpace(this.world, this.world.getSeed());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IRenderHandler getSkyRenderer() {

        //Maybe a little hacky
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player != null) {
            Entity e = player.getRidingEntity();
            if (e instanceof EntityRocket) {
                if (((EntityRocket) e).getInSpaceFlight()) {
                    if (!(skyRender instanceof RenderSpaceTravelSky))
                        skyRender = new RenderSpaceTravelSky();
                    return skyRender;
                }
            }
        }


        if (StellurgyConfiguration.getCurrentConfig().stationSkyOverride)
            return (skyRender == null || !(skyRender instanceof RenderSpaceSky)) ? skyRender = new RenderSpaceSky() : skyRender;

        return super.getSkyRenderer();
    }

    @Override
    public float getAtmosphereDensity(BlockPos pos) {
        return 0;
    }

    @Override
    public float calculateCelestialAngle(long worldTime, float p_76563_3_) {
        return Stellurgy.proxy.calculateCelestialAngleSpaceStation();
    }

    @Override
    public float getSunBrightness(float partialTicks) {
        DimensionProperties properties = getDimensionProperties(Minecraft.getMinecraft().player.getPosition());
        SpaceStationObject spaceStation = (SpaceStationObject) getSpaceObject(Minecraft.getMinecraft().player.getPosition());

        if (spaceStation != null) {
            //Vary brightness depending upon sun luminosity and planet distance
            //This takes into account how eyes work, that they're not linear in sensing light
            float preWarpBrightnessMultiplier = (float) AstronomicalBodyHelper.getPlanetaryLightLevelMultiplier(AstronomicalBodyHelper.getStellarBrightness(properties.getStar(), properties.getSolarOrbitalDistance()));
            //Warp is no light, because there are no stars
            return (spaceStation.isWarping()) ? (float) 0.0 : preWarpBrightnessMultiplier * world.getSunBrightnessBody(partialTicks);
        }
        return 0;
    }

    @Override
    protected void init() {
        this.hasSkyLight = true;
        world.getWorldInfo().setTerrainType(Stellurgy.spaceWorldType);

        this.biomeProvider = new BiomeProviderSingle(StellurgyBiomes.spaceBiome);//new ChunkManagerPlanet(worldObj, worldObj.getWorldInfo().getGeneratorOptions(), DimensionManager.getInstance().getDimensionProperties(worldObj.provider.getDimension()).getBiomes());

    }

    public ISpaceObject getSpaceObject(BlockPos pos) {
        return SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
    }

    @Override
    public DimensionProperties getDimensionProperties(@Nullable BlockPos pos) {
        if (pos != null) {
            ISpaceObject spaceObject = getSpaceObject(pos);
            if (spaceObject != null)
                return (DimensionProperties) spaceObject.getProperties();
        }
        return DimensionManager.defaultSpaceDimensionProperties;
    }
}
