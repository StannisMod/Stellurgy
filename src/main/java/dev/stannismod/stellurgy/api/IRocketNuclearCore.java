package dev.stannismod.stellurgy.api;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Must be implemented by any block that is to be treated like a nuclear core: a reactor rated in
 * thermal POWER and temperature that heats the working fluid of the nuclear nozzle it stands behind
 * (C10 STAT-35, STAT-36).
 */
public interface IRocketNuclearCore {
    /** The thermal power the core delivers now, watts; zero once it holds no fuel with burn-up left. */
    double getThermalPower(IBlockAccess world, BlockPos pos);

    /** The temperature the core runs at, kelvin. */
    double getCoreTemperature(IBlockAccess world, BlockPos pos);

    /**
     * Spends {@code joules} of the core's fuel — the energy it produced while a nozzle drew on it —
     * and answers the energy it could NOT supply, which is non-zero only when its fuel ran out first.
     */
    double produce(IBlockAccess world, BlockPos pos, double joules);
}
