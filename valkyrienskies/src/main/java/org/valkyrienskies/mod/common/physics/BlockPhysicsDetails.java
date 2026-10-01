package org.valkyrienskies.mod.common.physics;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.block.IBlockForceProvider;
import org.valkyrienskies.mod.common.block.IBlockTorqueProvider;
import org.valkyrienskies.mod.common.config.VSConfig;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class BlockPhysicsDetails {

    static final String BLOCK_MASS_VERSION = "v0.1";
    // A 1x1x1 cube of DEFAULT is 500kg.
    private final static double DEFAULT_MASS = 500D;

    /**
     * Blocks mapped to their mass: the built-in masses plus the configured overrides. Written whole
     * by {@link #syncWithConfig}, never edited in place, so the physics thread reads either the old
     * table or the new one. {@code null} until the mod's init has built it.
     */
    private static volatile Map<Block, Double> blockToMass;
    /**
     * Material mapped to their mass. Built from vanilla's material constants and literals only, which
     * nothing a server, a config or a registry remap can change, so it is the same table in every
     * lifetime whenever this class happens to load.
     */
    private static final Map<Material, Double> materialMass = materialMasses();

    /**
     * Rebuild the block-mass table from the built-in masses and the configured overrides, and swap
     * it in whole. The mod runs this at init, once every block is registered so a modded override
     * resolves; a config reload runs it again, which is the config reload's partial
     * re-initialisation of the mod, the sanctioned exception to statics being written once.
     */
    public static void syncWithConfig() {
        Map<Block, Double> rebuilt = new HashMap<>();
        rebuilt.put(Blocks.AIR, 0.0);
        rebuilt.put(Blocks.FIRE, 0.0);
        rebuilt.put(Blocks.FLOWING_WATER, 0.0);
        rebuilt.put(Blocks.FLOWING_LAVA, 0.0);
        rebuilt.put(Blocks.WATER, 0.0);
        rebuilt.put(Blocks.LAVA, 0.0);
        rebuilt.put(Blocks.BEDROCK, 50000.0);
        Arrays.stream(VSConfig.blockMass)
            .map(str -> str.split("="))
            .filter(arr -> arr.length == 2)
            .forEach(arr ->
                rebuilt.put(Block.getBlockFromName(arr[0]), Double.parseDouble(arr[1])));
        blockToMass = Collections.unmodifiableMap(rebuilt);
    }

    /** Whether ship assembly leaves {@code block} out of the ship rather than infusing it. */
    public static boolean isNotPhysicsInfused(Block block) {
        return block == Blocks.AIR || block == Blocks.WATER || block == Blocks.FLOWING_WATER
                || block == Blocks.LAVA || block == Blocks.FLOWING_LAVA;
    }

    private static Map<Material, Double> materialMasses() {
        Map<Material, Double> materialMass = new HashMap<>();
        materialMass.put(Material.AIR, 0.0);
        materialMass.put(Material.ANVIL, 8000.0);
        materialMass.put(Material.BARRIER, 0.0);
        materialMass.put(Material.CACTUS, 400.0);
        materialMass.put(Material.CAKE, 100.0);
        materialMass.put(Material.CARPET, 100.0);
        materialMass.put(Material.CIRCUITS, 200.0);
        materialMass.put(Material.CLAY, 2000.0);
        materialMass.put(Material.CLOTH, 100.0);
        materialMass.put(Material.CORAL, 2000.0);
        materialMass.put(Material.CRAFTED_SNOW, 500.0);
        materialMass.put(Material.DRAGON_EGG, 500.0);
        materialMass.put(Material.FIRE, 0.0);
        materialMass.put(Material.GLASS, 2000.0);
        materialMass.put(Material.GOURD, 1500.0);
        materialMass.put(Material.GRASS, 1500.0);
        materialMass.put(Material.GROUND, 1500.0);
        materialMass.put(Material.ICE, 500.0);
        materialMass.put(Material.IRON, 8000.0);
        materialMass.put(Material.LAVA, 2500.0);
        materialMass.put(Material.LEAVES, 100.0);
        materialMass.put(Material.PACKED_ICE, 500.0);
        materialMass.put(Material.PISTON, 3000.0);
        materialMass.put(Material.PLANTS, 300.0);
        materialMass.put(Material.PORTAL, 0.0);
        materialMass.put(Material.REDSTONE_LIGHT, 100.0);
        materialMass.put(Material.ROCK, 3000.0);
        materialMass.put(Material.SAND, 2000.0);
        materialMass.put(Material.SNOW, 500.0);
        materialMass.put(Material.SPONGE, 100.0);
        materialMass.put(Material.STRUCTURE_VOID, 0.0);
        materialMass.put(Material.TNT, 2000.0);
        materialMass.put(Material.VINE, 300.0);
        materialMass.put(Material.WATER, 1000.0);
        materialMass.put(Material.WEB, 100.0);
        materialMass.put(Material.WOOD, 500.0);
        return Collections.unmodifiableMap(materialMass);
    }

    /**
     * Get block mass, in kg.
     */
    public static double getMassFromState(IBlockState state) {
        return getMassOfBlock(state.getBlock());
    }

    private static double getMassOfMaterial(Material material) {
        return materialMass.getOrDefault(material, DEFAULT_MASS);
    }

    private static double getMassOfBlock(Block block) {
        Map<Block, Double> masses = blockToMass;
        if (masses == null) {
            throw new IllegalStateException("block masses read before the mod's init built them");
        }
        Double mass = masses.get(block);
        if (block instanceof BlockLiquid) {
            return 0D;
        } else if (mass != null) {
            return mass;
        } else {
            return getMassOfMaterial(block.blockMaterial);
        }
    }

    /**
     * Assigns the output parameter of toSet to be the force Vector for the given IBlockState.
     */
    static void getForceFromState(IBlockState state, BlockPos pos, World world,
        double secondsToApply,
        PhysicsObject obj, Vector3d toSet) {
        Block block = state.getBlock();
        if (block instanceof IBlockForceProvider) {
            Vector3dc forceVector = ((IBlockForceProvider) block).getBlockForceInWorldSpace(world, pos, state,
                    obj, secondsToApply);
            if (forceVector == null) {
                toSet.zero();
            } else {
                toSet.x = forceVector.x();
                toSet.y = forceVector.y();
                toSet.z = forceVector.z();
            }
        }
    }

    /**
     * Returns true if the given IBlockState can create force; otherwise it returns false.
     */
    public static boolean isBlockProvidingForce(IBlockState state) {
        Block block = state.getBlock();
        return block instanceof IBlockForceProvider || block instanceof IBlockTorqueProvider;
    }

}
