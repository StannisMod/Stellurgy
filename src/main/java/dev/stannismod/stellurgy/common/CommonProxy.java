package dev.stannismod.stellurgy.common;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.tile.atmosphere.TileAtmosphereDetector;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleButton;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleContainerPanYOnly;
import dev.stannismod.stellurgy.network.PacketLaserGun;
import dev.stannismod.stellurgy.network.PacketStationUpdate;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;

import java.util.List;
import java.util.LinkedList;

public class CommonProxy {

    private static final dev.stannismod.stellurgy.dimension.DimensionManager dimensionManagerServer = new dev.stannismod.stellurgy.dimension.DimensionManager();

    public void registerRenderers() {

    }

    public void registerEventHandlers() {

    }


    public ModuleBase createScrollListPan(
            int baseX, int baseY,
            List<ModuleBase> list,
            int sizeX, int sizeY
    ) {
        return new ModuleContainerPanYOnly(
                baseX, baseY,
                list, new LinkedList<>(),
                null,
                sizeX - 2, sizeY,
                0, -48,
                0, 72
        );
    }

    /** Generic clear for any UI scroll cache (no-op on server) */
    public void clearScrollCache() {
        // no-op on server/common
    }

    // Keep existing Observatory API working (optional wrappers)
    public ModuleBase createObservatoryAsteroidListPan(int baseX, int baseY, List<ModuleBase> list2, int sizeX, int sizeY) {
        return createScrollListPan(baseX, baseY, list2, sizeX, sizeY);
    }

    public void clearObservatoryScrollCache() {
        clearScrollCache();
    }

    public void spawnParticle(String particle, World world, double x, double y,
                              double z, double motionX, double motionY, double motionZ) {

    }

    public void spawnDynamicRocketSmoke(World world, double x, double y,
                                        double z, double motionX, double motionY, double motionZ, int engineNum) {

    }

    public void spawnDynamicRocketFlame(World world, double x, double y,
                                        double z, double motionX, double motionY, double motionZ, int engineNum) {

    }

    public void registerKeyBindings() {

    }

    public Profiler getProfiler() {
        return FMLCommonHandler.instance().getMinecraftServerInstance().profiler;
    }

    public void changeClientPlayerWorld(World world) {

    }

    public void fireFogBurst(ISpaceObject station) {
        PacketHandler.sendToNearby(new PacketStationUpdate(station, PacketStationUpdate.Type.SIGNAL_WHITE_BURST), StellurgyConfiguration.getCurrentConfig().spaceDimId, station.getSpawnLocation().x, 128, station.getSpawnLocation().z, StellurgyConfiguration.getCurrentConfig().stationSize);
    }


    public float calculateCelestialAngleSpaceStation() {
        return 0;
    }

    public long getWorldTimeUniversal(int id) {
        if (DimensionManager.getWorld(id) != null)
            return DimensionManager.getWorld(id).getTotalWorldTime();
        return 0;
    }

    public void preinit() {
        // TODO Auto-generated method stub

    }

    public void init() {
        // TODO Auto-generated method stub

    }

    public void spawnLaser(Entity entity, Vec3d toPos) {
        PacketHandler.sendToPlayersTrackingEntity(new PacketLaserGun(entity, toPos), entity);
    }

    public void loadUILayout(
            net.minecraftforge.common.config.Configuration config) {
        // TODO Auto-generated method stub

    }

    public void displayMessage(String msg, int time) {

    }

    public void preInitBlocks() {
        // TODO Auto-generated method stub

    }

    public void preInitItems() {
        // TODO Auto-generated method stub

    }

    public String getNameFromBiome(Biome biome) {
        return "";
    }

    public dev.stannismod.stellurgy.dimension.DimensionManager getDimensionManager() {
        return dimensionManagerServer;
    }

    // atmosphere detector

    public ModuleBase createAtmosphereDetectorButton(int offsetX, int offsetY, int buttonId, dev.stannismod.stellurgy.api.atmosphere.AtmosphereAssertion assertion, String text, TileAtmosphereDetector detector, ResourceLocation[] buttonImages) {
        return new ModuleButton(offsetX, offsetY, buttonId, text, detector, buttonImages);
    }

    public void sendClientStatusMessage(String translationKey, Object... args) {
        // Dedicated server: no-op
    }
}
