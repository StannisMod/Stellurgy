package dev.stannismod.stellurgy.util;

import dev.stannismod.stellurgy.api.IRocketEngine;
import dev.stannismod.stellurgy.api.IRocketNuclearCore;
import dev.stannismod.stellurgy.api.StatsRocket;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;
import dev.stannismod.stellurgy.api.propulsion.EngineDesign;
import dev.stannismod.stellurgy.block.BlockBipropellantRocketMotor;
import dev.stannismod.stellurgy.block.BlockNuclearRocketMotor;
import dev.stannismod.stellurgy.block.BlockRocketMotor;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * What a tier-1 craft's engines and cores add up to, for the three places that scan one (the rocket
 * assembler, the unmanned-vehicle assembler and the packed {@link StorageChunk}): rated thrust per
 * tank role, the cluster's cycle fraction, and the power of the nuclear core stacks (C10 STAT-30,
 * STAT-35, STAT-36).
 */
public final class RocketEngineTally {

    private final long[] thrust = new long[3];
    /** Per role, the sum of each motor's thrust over its cycle fraction: the cluster's flow is this over v_e. */
    private final double[] thrustOverCycle = new double[3];
    private double nuclearJetEfficiencyThrust;
    private double corePower;
    private double coreTemperatureSum;
    private int cores;

    private static int index(FuelType role) {
        switch (role) {
            case LIQUID_MONOPROPELLANT:
                return 0;
            case LIQUID_BIPROPELLANT:
                return 1;
            default:
                return 2;
        }
    }

    private static FuelType roleAt(int index) {
        return index == 0 ? FuelType.LIQUID_MONOPROPELLANT
                : index == 1 ? FuelType.LIQUID_BIPROPELLANT : FuelType.NUCLEAR_WORKING_FLUID;
    }

    /** The tank role an engine block feeds from, or {@code null} for an engine this scan does not know. */
    public static FuelType roleOf(Block block) {
        if (block instanceof BlockNuclearRocketMotor) {
            return FuelType.NUCLEAR_WORKING_FLUID;
        }
        if (block instanceof BlockBipropellantRocketMotor) {
            return FuelType.LIQUID_BIPROPELLANT;
        }
        if (block instanceof BlockRocketMotor) {
            return FuelType.LIQUID_MONOPROPELLANT;
        }
        return null;
    }

    /** Counts the engine at {@code pos}, its thrust scaled by {@code thrustFactor} (wear). */
    public void addEngine(Block block, World world, BlockPos pos, float thrustFactor) {
        FuelType role = roleOf(block);
        if (role == null || !(block instanceof IRocketEngine)) {
            return;
        }
        EngineDesign design = ((IRocketEngine) block).getEngineDesign(world, pos);
        double f = design.maxThrust() * thrustFactor;
        int i = index(role);
        thrust[i] += (long) f;
        thrustOverCycle[i] += f / design.cycleEfficiency();
        if (role == FuelType.NUCLEAR_WORKING_FLUID) {
            nuclearJetEfficiencyThrust += f * design.jetEfficiency();
        }
    }

    /** Counts a core whose stack feeds a nozzle. */
    public void addCore(IRocketNuclearCore core, IBlockAccess world, BlockPos pos) {
        double p = core.getThermalPower(world, pos);
        corePower += p;
        coreTemperatureSum += core.getCoreTemperature(world, pos);
        cores++;
    }

    /**
     * Whether the core at {@code pos} stands in a column of cores that ends, below, on a nuclear
     * nozzle: the stack it belongs to is the one that nozzle draws on (a tier-1 nozzle exhausts down).
     */
    public static boolean coreFeedsNozzle(IBlockAccess world, BlockPos pos) {
        BlockPos below = pos.down();
        while (below.getY() >= 0 && world.getBlockState(below).getBlock() instanceof IRocketNuclearCore) {
            below = below.down();
        }
        return below.getY() >= 0 && world.getBlockState(below).getBlock() instanceof BlockNuclearRocketMotor;
    }

    /** Rated thrust of the engines feeding from {@code role}, N. */
    public long thrust(FuelType role) {
        return thrust[index(role)];
    }

    /** How many tank roles have engines — more than one is a combined-thrust craft. */
    public int rolesWithThrust() {
        int n = 0;
        for (long t : thrust) {
            if (t > 0) {
                n++;
            }
        }
        return n;
    }

    /** The counted cores' thermal power now, W. */
    public double corePower() {
        return corePower;
    }

    /** Writes the engines into {@code stats}: the role with the most thrust is the one the craft burns. */
    public void applyTo(StatsRocket stats) {
        int best = -1;
        for (int i = 0; i < thrust.length; i++) {
            if (thrust[i] > 0 && (best < 0 || thrust[i] > thrust[best])) {
                best = i;
            }
        }
        if (best < 0) {
            stats.setThrust(0);
            stats.setEngineFeed(null);
            stats.setExhaustVelocityFactor(1);
            stats.setNuclear(0, 0, 0);
            return;
        }
        stats.setThrust(thrust[best]);
        stats.setEngineFeed(roleAt(best));
        stats.setExhaustVelocityFactor((float) (thrust[best] / thrustOverCycle[best]));
        int n = index(FuelType.NUCLEAR_WORKING_FLUID);
        stats.setNuclear(corePower, cores > 0 ? coreTemperatureSum / cores : 0,
                thrust[n] > 0 ? (float) (nuclearJetEfficiencyThrust / thrust[n]) : 0);
    }
}
