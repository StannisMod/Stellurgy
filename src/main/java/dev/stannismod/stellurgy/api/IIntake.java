package dev.stannismod.stellurgy.api;

import net.minecraft.block.state.IBlockState;

public interface IIntake {
    int getIntakeAmt(IBlockState state);
}
