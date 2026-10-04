package dev.stannismod.stellurgy.world.ore;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.feature.WorldGenMinable;
import net.minecraft.world.gen.feature.WorldGenerator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.OreGenEvent;
import net.minecraftforge.event.terraingen.OreGenEvent.GenerateMinable.EventType;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.eventhandler.Event.Result;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.world.provider.WorldProviderPlanet;
import dev.stannismod.stellurgy.libvulpes.api.material.AllowedProducts;
import dev.stannismod.stellurgy.libvulpes.api.material.Material;
import dev.stannismod.stellurgy.libvulpes.api.material.MaterialRegistry;

import java.util.Random;

public class OreGenerator extends WorldGenerator implements IWorldGenerator {


    private void generate(World world, Material material, int numPerChunk, int clumpSize, int chunkX, int chunkZ, Random random) {
        for (int i = 0; i < numPerChunk; i++) {
            int coordX = 16 * chunkX + random.nextInt(16);
            int coordY = random.nextInt(64);
            int coordZ = 16 * chunkZ + random.nextInt(16);

            Block block = Block.getBlockFromItem(material.getProduct(AllowedProducts.getProductByName("ORE")).getItem());

            new WorldGenMinable(block.getStateFromMeta(material.getMeta()),
                    clumpSize).generate(world, random, new BlockPos(coordX, coordY, coordZ));
        }
    }

    @Override
    public void generate(Random random, int chunkX, int chunkZ, World world,
                         IChunkGenerator chunkGenerator, IChunkProvider chunkProvider) {

        OreGenEvent event = new OreGenEvent.GenerateMinable(world, random, this, new BlockPos(chunkX, 0, chunkZ), EventType.CUSTOM);
        MinecraftForge.ORE_GEN_BUS.post(event);
        if (event.getResult() != Result.DENY) {
            if (StellurgyConfiguration.getCurrentConfig().generateCopper) {
                generate(world, MaterialRegistry.getMaterialFromName("Copper"), StellurgyConfiguration.getCurrentConfig().copperPerChunk, StellurgyConfiguration.getCurrentConfig().copperClumpSize, chunkX, chunkZ, random);
            }

            if (StellurgyConfiguration.getCurrentConfig().generateTin) {
                generate(world, MaterialRegistry.getMaterialFromName("Tin"), StellurgyConfiguration.getCurrentConfig().tinPerChunk, StellurgyConfiguration.getCurrentConfig().tinClumpSize, chunkX, chunkZ, random);
            }
            if (StellurgyConfiguration.getCurrentConfig().generateRutile) {
                generate(world, MaterialRegistry.getMaterialFromName("Rutile"), StellurgyConfiguration.getCurrentConfig().rutilePerChunk, StellurgyConfiguration.getCurrentConfig().rutileClumpSize, chunkX, chunkZ, random);
            }
            if (StellurgyConfiguration.getCurrentConfig().generateAluminum) {
                generate(world, MaterialRegistry.getMaterialFromName("Aluminum"), StellurgyConfiguration.getCurrentConfig().aluminumPerChunk, StellurgyConfiguration.getCurrentConfig().aluminumClumpSize, chunkX, chunkZ, random);
            }
            if (StellurgyConfiguration.getCurrentConfig().generateIridium) {
                generate(world, MaterialRegistry.getMaterialFromName("Iridium"), StellurgyConfiguration.getCurrentConfig().IridiumPerChunk, StellurgyConfiguration.getCurrentConfig().IridiumClumpSize, chunkX, chunkZ, random);
            }

            if (StellurgyConfiguration.getCurrentConfig().generateDilithium) {
                int dilithiumChance = StellurgyConfiguration.getCurrentConfig().dilithiumPerChunk;
                if (world.provider instanceof WorldProviderPlanet) {
                    dilithiumChance = DimensionProperties.AtmosphereTypes.getAtmosphereTypeFromValue(DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension()).getAtmosphereDensity()) == DimensionProperties.AtmosphereTypes.NONE ? StellurgyConfiguration.getCurrentConfig().dilithiumPerChunkMoon : StellurgyConfiguration.getCurrentConfig().dilithiumPerChunk;
                }

                for (int i = 0; i < dilithiumChance; i++) {
                    int coordX = 16 * chunkX + random.nextInt(16);
                    int coordY = random.nextInt(64);
                    int coordZ = 16 * chunkZ + random.nextInt(16);

                    new WorldGenMinable(MaterialRegistry.getMaterialFromName("Dilithium").getBlock().getDefaultState(), StellurgyConfiguration.getCurrentConfig().dilithiumClumpSize).generate(world, random, new BlockPos(coordX, coordY, coordZ));
                }
            }
        }
    }

    @Override
    public boolean generate(World worldIn, Random rand, BlockPos position) {
        return false;
    }
}
