package dev.stannismod.stellurgy.probe;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * The first probe's flight recorder, taken out of its wreck: it carries the probe's record — its readings and
 * its failure journal — for a satellite terminal to read.
 */
public class ItemFlightRecorder extends Item {

    private static final String RECORD_KEY = "record";

    public ItemFlightRecorder() {
        setMaxStackSize(1);
    }

    /** A recorder holding {@code record}. */
    public ItemStack holding(NBTTagCompound record) {
        ItemStack stack = new ItemStack(this);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(RECORD_KEY, record.copy());
        stack.setTagCompound(tag);
        return stack;
    }

    /** The record {@code stack} carries; empty for a stack that is no recorder or holds none. */
    public static Optional<ProbeRecord> recordOf(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemFlightRecorder) || !stack.hasTagCompound()
                || !stack.getTagCompound().hasKey(RECORD_KEY)) {
            return Optional.empty();
        }
        return Optional.of(ProbeRecord.readFromNBT(stack.getTagCompound().getCompoundTag(RECORD_KEY)));
    }

    /** What a pack added to the record {@code stack} carries; {@link ProbePack#NONE} when it added nothing. */
    public static ProbePack packOf(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemFlightRecorder) || !stack.hasTagCompound()) {
            return ProbePack.NONE;
        }
        NBTTagCompound record = stack.getTagCompound().getCompoundTag(RECORD_KEY);
        return record.hasKey(ProbeRecord.PACK_KEY) ? ProbePack.readFromNBT(record.getCompoundTag(ProbeRecord.PACK_KEY))
                : ProbePack.NONE;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        Optional<ProbeRecord> record = recordOf(stack);
        if (record.isPresent()) {
            tooltip.add(I18n.format("item.flightRecorder.readings", record.get().readings().recorded()));
        } else {
            tooltip.add(I18n.format("item.flightRecorder.empty"));
        }
        tooltip.addAll(packOf(stack).notes());
    }
}
