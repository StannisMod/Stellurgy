package dev.stannismod.stellurgy.probe;

import dev.stannismod.stellurgy.client.ClientWorldDrawings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.IItemPropertyGetter;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * A direction-finding radio: its needle points along the bearing of the strongest transmitter in the world it
 * is held in, and used it reports that signal's strength — never a distance. With nothing to hear, the needle
 * wanders.
 */
public class ItemRadio extends Item {

    public ItemRadio() {
        setMaxStackSize(1);
        addPropertyOverride(new ResourceLocation("angle"), new Needle());
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        if (world.isRemote) {
            Optional<RadioReading> reading = RadioReading.at(player.posX, player.posY + player.getEyeHeight(), player.posZ,
                    ClientWorldDrawings.of(world).transmitters().positions());
            player.sendStatusMessage(reading.isPresent()
                    ? new TextComponentTranslation("msg.radio.signal", String.format("%.1f", reading.get().strengthDbm))
                    : new TextComponentTranslation("msg.radio.silent"), true);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }

    /** The needle: the bearing turned into the held item's frame, eased as a compass needle eases. */
    private static final class Needle implements IItemPropertyGetter {
        @SideOnly(Side.CLIENT)
        private double rotation;
        @SideOnly(Side.CLIENT)
        private double rate;
        @SideOnly(Side.CLIENT)
        private long lastTick;

        @Override
        @SideOnly(Side.CLIENT)
        public float apply(ItemStack stack, @Nullable World world, @Nullable EntityLivingBase holder) {
            Entity entity = holder != null ? holder : stack.getItemFrame();
            if (entity == null) {
                return 0f;
            }
            World in = world != null ? world : entity.world;
            Optional<RadioReading> reading = RadioReading.at(entity.posX, entity.posY + entity.getEyeHeight(), entity.posZ,
                    ClientWorldDrawings.of(in).transmitters().positions());
            double target;
            if (reading.isPresent()) {
                double yaw = holder != null ? entity.rotationYaw
                        : MathHelper.wrapDegrees(180 + stack.getItemFrame().facingDirection.getHorizontalIndex() * 90);
                double facing = MathHelper.positiveModulo(yaw / 360d, 1d);
                target = 0.5d - (facing - 0.25d - reading.get().bearingRad / (2d * Math.PI));
            } else {
                target = in.rand.nextDouble();
            }
            if (in.getTotalWorldTime() != lastTick) {
                lastTick = in.getTotalWorldTime();
                double turn = MathHelper.positiveModulo(target - rotation + 0.5d, 1d) - 0.5d;
                rate = (rate + turn * 0.1d) * 0.8d;
                rotation = MathHelper.positiveModulo(rotation + rate, 1d);
            }
            return MathHelper.positiveModulo((float) rotation, 1f);
        }
    }
}
