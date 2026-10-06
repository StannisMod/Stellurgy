package dev.stannismod.stellurgy.damage.repair;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import dev.stannismod.stellurgy.api.damage.IRepairBayPart;

/**
 * A repair bay frame: placed against a bay controller, or against another frame that is, it makes
 * that bay bigger. It holds nothing and is asked nothing; the controller counts it.
 */
public class BlockRepairBayPart extends Block implements IRepairBayPart {

    public BlockRepairBayPart() {
        super(Material.IRON);
        setHardness(3.0F);
        setResistance(10.0F);
    }
}
