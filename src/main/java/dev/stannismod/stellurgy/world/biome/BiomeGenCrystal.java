package dev.stannismod.stellurgy.world.biome;

import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.feature.WorldGenerator;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.world.decoration.MapGenLargeCrystal;
import dev.stannismod.stellurgy.world.gen.WorldGenLargeCrystal;

import javax.annotation.Nonnull;
import java.util.Random;

public class BiomeGenCrystal extends Biome {

    WorldGenerator crystalGenerator;

    public BiomeGenCrystal(BiomeProperties properties) {
        super(properties);

        topBlock = Blocks.SNOW.getDefaultState();
        fillerBlock = Blocks.PACKED_ICE.getDefaultState();
        this.spawnableMonsterList.clear();
        this.spawnableCreatureList.clear();
        this.decorator.generateFalls = false;
        this.decorator.flowersPerChunk = 0;
        this.decorator.grassPerChunk = 0;
        this.decorator.treesPerChunk = 0;
        this.decorator.mushroomsPerChunk = 0;

        crystalGenerator = new WorldGenLargeCrystal();
    }

    @Override
    public void genTerrainBlocks(World worldIn, Random rand,
                                 @Nonnull ChunkPrimer chunkPrimerIn, int x, int z, double noiseVal) {
        super.genTerrainBlocks(worldIn, rand, chunkPrimerIn, x, z, noiseVal);

        // A MapGenBase keeps the world it last generated into; built per chunk, so the biome - one
        // object for the whole process - holds no world.
        if (x % 16 == 0 && z % 16 == 0)
            new MapGenLargeCrystal(fillerBlock, StellurgyBlocks.blockCrystal.getDefaultState())
                    .generate(worldIn, x >> 4, z >> 4, chunkPrimerIn);
    }
}
