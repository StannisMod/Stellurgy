package zmaster587.advancedRocketry.atmosphere;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.network.PacketOxygenState;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.network.PacketHandler;

/**
 * Atmosphere type for vaccum (No air)
 *
 * @author Zmaster
 */
public class AtmosphereVacuum extends AtmosphereNeedsSuit {

    public AtmosphereVacuum() {
        super(true, false, false, "vacuum");
    }

    @Override
    public void onTick(EntityLivingBase player) {
        if (player.world.getTotalWorldTime() % 10 == 0 && !isImmune(player)) {
            player.attackEntityFrom(AtmosphereHandler.vacuumDamage,
                    ARConfiguration.getCurrentConfig().vacuumDamage);
            player.addPotionEffect(new PotionEffect(Potion.getPotionById(2), 40, 4));
            player.addPotionEffect(new PotionEffect(Potion.getPotionById(4), 40, 4));
            // The config IN FORCE, not the one this JVM booted with; see AtmosphereNoOxygen.
            if (ARConfiguration.getCurrentConfig().enableNausea) {
                player.addPotionEffect(new PotionEffect(Potion.getPotionById(9), 400, 1));
            }
            if (player instanceof EntityPlayer)
                AtmosphereType.sendToRealPlayer(new PacketOxygenState(), (EntityPlayer) player);
        }
    }

    @Override
    public String getDisplayMessage() {
        return LibVulpes.proxy.getLocalizedString("msg.noOxygen");
    }
}
