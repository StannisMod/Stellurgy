package dev.stannismod.stellurgy.affs.item;

import dev.stannismod.stellurgy.affs.block.BlockFieldGenerator;
import net.minecraft.block.Block;

public class ItemBlockFieldGenerator extends ItemBlockTiered {

    public ItemBlockFieldGenerator(Block block) {
        super(block, BlockFieldGenerator.TIER_COUNT);
    }
}
