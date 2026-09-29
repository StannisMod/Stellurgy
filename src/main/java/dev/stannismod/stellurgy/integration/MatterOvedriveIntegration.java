package dev.stannismod.stellurgy.integration;

import matteroverdrive.entity.*;
import matteroverdrive.entity.monster.EntityMeleeRougeAndroidMob;
import matteroverdrive.entity.monster.EntityRangedRogueAndroidMob;
import matteroverdrive.entity.player.MOPlayerCapabilityProvider;
import matteroverdrive.init.OverdriveBioticStats;
import net.minecraft.entity.EntityLivingBase;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;

public class MatterOvedriveIntegration {
    public static boolean isAndroidNeedNoOxygen(EntityLivingBase player) {
        return (MOPlayerCapabilityProvider.GetAndroidCapability(player) != null
                && MOPlayerCapabilityProvider.GetAndroidCapability(player).isAndroid()
                && MOPlayerCapabilityProvider.GetAndroidCapability(player).isUnlocked(OverdriveBioticStats.oxygen, 1));
    }

    public static void addAndroidsToBypassList(StellurgyConfiguration stellurgyConfig) {
        // for some reason Stellurgy cant register MO entities to bypass, so we just make this a dummy method

        stellurgyConfig.bypassEntity.add(EntityDrone.class);
        stellurgyConfig.bypassEntity.add(EntityRangedRogueAndroidMob.class);
        stellurgyConfig.bypassEntity.add(EntityMeleeRougeAndroidMob.class);
    }
}
