package dev.stannismod.stellurgy.atmosphere;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.network.PacketOxygenState;
import zmaster587.libVulpes.LibVulpes;

public class AtmosphereVeryHotNoOxygen extends AtmosphereNeedsSuit {

    public AtmosphereVeryHotNoOxygen(boolean canTick, boolean isBreathable, boolean allowsCombustion,
                                     String name) {
        super(canTick, isBreathable, allowsCombustion, name);
    }


    @Override
    public String getDisplayMessage() {
        return LibVulpes.proxy.getLocalizedString("msg.noOxygen");
    }

    // Needs full pressure suit
    protected boolean onlyNeedsMask() {
        return false;
    }

    @Override
    public void onTick(EntityLivingBase player) {
        if (player.world.getTotalWorldTime() % 10 == 0 && !isImmune(player)) {
            player.attackEntityFrom(AtmosphereHandler.lowOxygenDamage, 1);
            if (player.world.getTotalWorldTime() % 20 == 0 && !isImmune(player)) {
                player.attackEntityFrom(AtmosphereHandler.heatDamage, 1);
            }
            player.addPotionEffect(new PotionEffect(Potion.getPotionById(2), 40, 4));
            player.addPotionEffect(new PotionEffect(Potion.getPotionById(4), 40, 4));
            // The config IN FORCE, not the one this JVM booted with; see AtmosphereNoOxygen.
            if (StellurgyConfiguration.getCurrentConfig().enableNausea) {
                player.addPotionEffect(new PotionEffect(Potion.getPotionById(9), 400, 1));
            }
            if (player instanceof EntityPlayer)
                AtmosphereType.sendToRealPlayer(new PacketOxygenState(), (EntityPlayer) player);
        }
    }
}
