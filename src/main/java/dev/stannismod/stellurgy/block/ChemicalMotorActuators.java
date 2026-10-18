package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.AtmosphereDensity;
import dev.stannismod.stellurgy.api.IRocketNuclearCore;
import dev.stannismod.stellurgy.api.capability.CapabilityWear;
import dev.stannismod.stellurgy.api.capability.IPartWear;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;
import dev.stannismod.stellurgy.api.propulsion.EngineDesign;
import dev.stannismod.stellurgy.api.propulsion.EngineRegime;
import dev.stannismod.stellurgy.api.propulsion.NuclearThermal;
import dev.stannismod.stellurgy.libvulpes.block.BlockFullyRotatable;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ChemicalMotor;
import dev.stannismod.stellurgy.ship.control.Propellant;
import dev.stannismod.stellurgy.util.WeightEngine;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the world tells a motor's {@link ChemicalMotor}: where its nozzle points, whether it still
 * works, which propellants could feed it, the power of the core stack behind a nuclear nozzle, and
 * the pressure of the air it fires into (C10 STAT-31, STAT-36, STAT-37).
 *
 * <p>The nozzle is the facing the motor is DRAWN with — its actual state, which follows the tank it
 * is fed from — so the push a pilot gets is the push the model on screen shows. Two motor families
 * share this and do not share a superclass, which is the only reason it is a helper and not an
 * inherited method.</p>
 */
final class ChemicalMotorActuators {

    private ChemicalMotorActuators() {}

    /** A chemical motor feeding from {@code role}: one regime per propellant the table can burn. */
    static void add(World world, BlockPos pos, IBlockState state, EngineDesign design, FuelType role,
                    List<Actuator> out) {
        WeightEngine table = Stellurgy.weights();
        Map<Propellant, EngineRegime> regimes = new LinkedHashMap<>();
        if (role == FuelType.LIQUID_BIPROPELLANT) {
            for (Fluid fuel : FuelRegistry.instance.getFluids(FuelType.LIQUID_BIPROPELLANT)) {
                for (Fluid ox : FuelRegistry.instance.getFluids(FuelType.LIQUID_OXIDIZER)) {
                    table.pairOf(fuel, ox).ifPresent(pair -> regimes.put(
                            Propellant.pair(fuel.getName(), ox.getName(), pair.mixtureRatio),
                            design.regimeFor(pair.exhaustVelocity)));
                }
            }
        } else {
            for (Fluid fluid : FuelRegistry.instance.getFluids(role)) {
                table.monopropellantExhaustVelocityOf(fluid)
                        .ifPresent(ve -> regimes.put(Propellant.of(fluid.getName()), design.regimeFor(ve)));
            }
        }
        emit(world, pos, state, regimes, 0, out);
    }

    /**
     * A nuclear nozzle: fed the power of the contiguous column of cores behind it, at the stack's
     * temperature, one regime per working fluid the table gives a molar mass.
     */
    static void addNuclear(World world, BlockPos pos, IBlockState state, EngineDesign design,
                           List<Actuator> out) {
        EnumFacing nozzle = facing(world, pos, state);
        double power = 0;
        double temperatureSum = 0;
        int cores = 0;
        BlockPos at = pos.offset(nozzle.getOpposite());
        Block block;
        while ((block = world.getBlockState(at).getBlock()) instanceof IRocketNuclearCore) {
            IRocketNuclearCore core = (IRocketNuclearCore) block;
            power += core.getThermalPower(world, at);
            temperatureSum += core.getCoreTemperature(world, at);
            cores++;
            at = at.offset(nozzle.getOpposite());
        }
        Map<Propellant, EngineRegime> regimes = new LinkedHashMap<>();
        if (cores > 0) {
            double temperature = temperatureSum / cores;
            WeightEngine table = Stellurgy.weights();
            for (Fluid fluid : FuelRegistry.instance.getFluids(FuelType.NUCLEAR_WORKING_FLUID)) {
                table.molarMassOf(fluid).ifPresent(m -> regimes.put(Propellant.of(fluid.getName()),
                        design.regimeFor(NuclearThermal.exhaustVelocity(temperature, m))));
            }
        }
        emit(world, pos, state, regimes, power, out);
    }

    private static void emit(World world, BlockPos pos, IBlockState state, Map<Propellant, EngineRegime> regimes,
                             double power, List<Actuator> out) {
        Vec3i d = facing(world, pos, state).getDirectionVec();
        out.add(ChemicalMotor.fromPropellants(pos.getX(), pos.getY(), pos.getZ(), d.getX(), d.getY(), d.getZ(),
                regimes, power, AtmosphereDensity.pascals(world)));
    }

    private static EnumFacing facing(World world, BlockPos pos, IBlockState state) {
        return state.getBlock().getActualState(state, world, pos).getValue(BlockFullyRotatable.FACING);
    }

    /** A motor worn to its last stage is broken and does not fire; anything short of that does. */
    static boolean working(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        IPartWear wear = CapabilityWear.get(te);
        return wear == null || wear.getStage() < wear.getMaxStage();
    }
}
