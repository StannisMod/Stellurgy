package dev.stannismod.stellurgy.integration.vs;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.util.WeightEngine;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tier-2 craft's propellant POOL as the world holds it (C10 STAT-22, STAT-38, STAT-40): the holders
 * aboard whose contents the mass frame counts — a tile's fluid capability on its unsided face, exactly
 * what {@link WeightEngine} weighs — and, in them, the fluids some drive can burn. One pool per fluid,
 * ship-wide; there is no feed network. A container whose contents live in item NBT weighs as empty and
 * is not in the pool, so no drive can eject mass the frame never had.
 *
 * <p>Built by the survey from the hull's blocks, in the ship's subspace frame; the holders are read again
 * every tick (a tank empties without a block changing) and are drained in position order, so the same
 * hull drains the same holder first on every run. Game thread only.</p>
 */
public final class ShipPropellant {

    private final List<BlockPos> holders;

    private ShipPropellant(List<BlockPos> holders) {
        this.holders = Collections.unmodifiableList(holders);
    }

    /**
     * Whether a tier-2 craft's drives CONSUME: the one switch, {@code rocketRequireFuel}, for both tiers
     * (C10 STAT-22). Off, the pool is never read, nothing is booked or drained, and every drive that
     * works is LIVE — and so is every consequence that reads consumption.
     */
    public static boolean metered() {
        return dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().rocketRequireFuel;
    }

    /** Whether {@code fluid} aboard is propellant rather than cargo: some reaction drive can burn it. */
    public static boolean isPropellant(@Nullable Fluid fluid) {
        return FuelRegistry.instance.isEnginePropellant(fluid);
    }

    /** The holders among {@code tiles}' positions; {@code world} is asked for each one's tile. */
    static ShipPropellant of(World world, List<BlockPos> candidates) {
        List<BlockPos> holders = new ArrayList<>();
        for (BlockPos p : candidates) {
            if (handler(world.getTileEntity(p)) != null) {
                holders.add(p.toImmutable());
            }
        }
        Collections.sort(holders);
        return new ShipPropellant(holders);
    }

    @Nullable
    private static IFluidHandler handler(@Nullable TileEntity tile) {
        return tile == null ? null : tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
    }

    /** The pool now, kg of each propellant fluid aboard; a fluid with none is absent. */
    public Map<String, Double> measure(World world) {
        WeightEngine table = Stellurgy.weights();
        Map<String, Double> kg = new TreeMap<>();
        for (BlockPos p : holders) {
            IFluidHandler h = handler(world.getTileEntity(p));
            if (h == null) {
                continue;
            }
            for (IFluidTankProperties tank : h.getTankProperties()) {
                FluidStack in = tank == null ? null : tank.getContents();
                if (in != null && in.amount > 0 && isPropellant(in.getFluid())) {
                    kg.merge(in.getFluid().getName(), in.amount * table.massPerMillibucket(in.getFluid()), Double::sum);
                }
            }
        }
        return kg;
    }

    /** What one drain took out: kg per fluid, and each holder's loss as a contributor at its block. */
    public static final class Drained {
        public final Map<String, Double> kg = new TreeMap<>();
        public final List<MassContributor> removed = new ArrayList<>();
    }

    /**
     * {@link #drain}, and the physics record of the ship named {@code physicsId} loses what was drained
     * at the holders it left, the same tick (C10 STAT-24, STAT-40) — the delta path for the one term the
     * slow round may not lag.
     */
    public Drained drainFromShip(World world, java.util.UUID physicsId, Map<String, Double> owedKg) {
        Drained out = drain(world, owedKg);
        if (!out.removed.isEmpty()) {
            org.valkyrienskies.mod.common.ships.ShipData ship = VSBridge.shipDataByUuid(world, physicsId);
            if (ship != null) {
                ShipInertiaWriter.removeFrom(ship.getInertiaData(), out.removed, String.valueOf(physicsId));
            }
        }
        return out;
    }

    /**
     * Take out of the holders, per fluid, the WHOLE millibuckets {@code owedKg} amounts to — never
     * rounded up, so the fraction stays owed for the next drain (C10 STAT-40).
     */
    public Drained drain(World world, Map<String, Double> owedKg) {
        WeightEngine table = Stellurgy.weights();
        Drained out = new Drained();
        for (Map.Entry<String, Double> owed : owedKg.entrySet()) {
            Fluid fluid = FluidRegistry.getFluid(owed.getKey());
            if (fluid == null) {
                continue;
            }
            double perMb = table.massPerMillibucket(fluid);
            int left = (int) Math.min(Integer.MAX_VALUE, Math.floor(owed.getValue() / perMb));
            for (BlockPos p : holders) {
                if (left <= 0) {
                    break;
                }
                IFluidHandler h = handler(world.getTileEntity(p));
                if (h == null) {
                    continue;
                }
                FluidStack taken = h.drain(new FluidStack(fluid, left), true);
                if (taken == null || taken.amount <= 0) {
                    continue;
                }
                left -= taken.amount;
                double kg = taken.amount * perMb;
                out.kg.merge(fluid.getName(), kg, Double::sum);
                out.removed.add(MassContributor.ofBlock(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, kg,
                        MassContributor.Kind.REACTION));
            }
        }
        return out;
    }
}
