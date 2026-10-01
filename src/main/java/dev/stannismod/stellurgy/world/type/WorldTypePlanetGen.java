package dev.stannismod.stellurgy.world.type;

import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.gen.IChunkGenerator;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.world.ChunkProviderPlanet;

public class WorldTypePlanetGen extends WorldType {

    public WorldTypePlanetGen(String name) {
        super("PlanetGen");
    }

    @Override
    public BiomeProvider getBiomeProvider(World world) {
        return null;//new ChunkManagerPlanet(world); //new WorldChunkManager(world);//
    }

    @Override
    public IChunkGenerator getChunkGenerator(World world, String generatorOptions) {
        return new ChunkProviderPlanet(world, world.getSeed(), StellurgyConfiguration.getCurrentConfig().generateVanillaStructures, generatorOptions);
    }


    @Override
    public boolean canBeCreated() {
        return false;
    }

}
