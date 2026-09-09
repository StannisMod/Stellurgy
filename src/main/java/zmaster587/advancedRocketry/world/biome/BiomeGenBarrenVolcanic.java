package zmaster587.advancedRocketry.world.biome;

import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;

public class BiomeGenBarrenVolcanic extends Biome {

    public BiomeGenBarrenVolcanic(BiomeProperties properties) {

        super(properties);

        spawnableMonsterList.clear();
        this.spawnableMonsterList.add(new Biome.SpawnListEntry(EntityCreeper.class, 5, 1, 1));
        this.spawnableCreatureList.clear();
        this.decorator.generateFalls = false;
        this.decorator.flowersPerChunk = 0;
        this.decorator.grassPerChunk = 0;
        this.decorator.treesPerChunk = 0;
        this.decorator.mushroomsPerChunk = 0;
        this.fillerBlock = this.topBlock = AdvancedRocketryBlocks.blockBasalt.getDefaultState();
    }

    @Override
    public int getSkyColorByTemp(float p_76731_1_) {
        return 0x332428;
    }

    @Override
    public int getGrassColorAtPos(BlockPos pos) {
        return 0x132113;
    }


}
