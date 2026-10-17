package dev.stannismod.stellurgy.api;

import dev.stannismod.stellurgy.api.propulsion.EngineDesign;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Must be implemented by any block that is to be treated like a rocket engine.
 */
public interface IRocketEngine {
    /**
     * What the engine at {@code pos} is built as: its family, rated thrust, cycle and bell. What it
     * gives in flight is that design fed its propellant ({@link EngineDesign#regimeFor}).
     */
    EngineDesign getEngineDesign(World world, BlockPos pos);
}
