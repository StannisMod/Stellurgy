package dev.stannismod.stellurgy.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One kilogram of reactor fuel, fresh or spent (C10 STAT-35). A fresh element counts the energy it
 * has produced; when that reaches what its U-235 can give by complete fission it is SPENT — the same
 * kilogram, now fission products, which nothing burns.
 */
public class ItemNuclearFuelElement extends Item {

    /** One element is one kilogram of fuel: the unit of the item, not a tuned number. */
    public static final double MASS_KG = 1.0;
    /** Highly enriched uranium as NERVA burnt it: 93 % U-235. */
    public static final double ENRICHMENT = 0.93;
    /** Complete fission of U-235, J/kg: about 200 MeV per fission. */
    public static final double FISSION_ENERGY_PER_KG = 8.2e13;
    /** What one element can give before it is spent, J. */
    public static final double CAPACITY_JOULES = MASS_KG * ENRICHMENT * FISSION_ENERGY_PER_KG;

    private static final String BURNUP = "burnupJoules";

    private final boolean spent;

    public ItemNuclearFuelElement(boolean spent) {
        this.spent = spent;
        setMaxStackSize(1);
    }

    /** Whether this is the spent form. */
    public boolean isSpent() {
        return spent;
    }

    /** The energy {@code stack} has produced so far, J; every spent element has produced its whole capacity. */
    public static double burnup(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemNuclearFuelElement)) {
            return 0;
        }
        if (((ItemNuclearFuelElement) stack.getItem()).spent) {
            return CAPACITY_JOULES;
        }
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? 0 : tag.getDouble(BURNUP);
    }

    /** Whether {@code stack} is a fresh element with burn-up left. */
    public static boolean hasBurnupLeft(ItemStack stack) {
        return stack.getItem() instanceof ItemNuclearFuelElement
                && !((ItemNuclearFuelElement) stack.getItem()).spent
                && burnup(stack) < CAPACITY_JOULES;
    }

    /** Records {@code joules} produced on a fresh {@code stack}; the caller swaps a full one for spent fuel. */
    public static void setBurnup(ItemStack stack, double joules) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setDouble(BURNUP, joules);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        if (!spent) {
            tooltip.add(String.format("%.1f%%", 100 * burnup(stack) / CAPACITY_JOULES));
        }
    }
}
