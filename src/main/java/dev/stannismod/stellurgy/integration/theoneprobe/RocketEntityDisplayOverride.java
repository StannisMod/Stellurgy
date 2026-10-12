package dev.stannismod.stellurgy.integration.theoneprobe;

import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;
import dev.stannismod.stellurgy.entity.EntityRocket;
import mcjty.theoneprobe.api.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

public class RocketEntityDisplayOverride implements IEntityDisplayOverride {

    @Override
    public boolean overrideStandardInfo(ProbeMode mode, IProbeInfo probeInfo,
                                        EntityPlayer player, World world,
                                        Entity entity, IProbeHitEntityData data) {
        if (!(entity instanceof EntityRocket)) {
            return false;
        }

        EntityRocket rocket = (EntityRocket) entity;
        probeInfo.text(TextStyleClass.NAME + getRocketDisplayName(rocket));
        probeInfo.text(TextStyleClass.MODNAME + tr("msg.top.stellurgy.modname"));
        return true;
    }

    private static String tr(String key) {
        return IProbeInfo.STARTLOC + key + IProbeInfo.ENDLOC;
    }

    private static String getRocketDisplayName(EntityRocket rocket) {
        FuelType mainFuel = rocket.getRocketFuelType();

        if (mainFuel == FuelType.LIQUID_MONOPROPELLANT) {
            return tr("msg.top.stellurgy.rocket.monopropellant");
        }
        if (mainFuel == FuelType.LIQUID_BIPROPELLANT) {
            return tr("msg.top.stellurgy.rocket.bipropellant");
        }
        if (mainFuel == FuelType.NUCLEAR_WORKING_FLUID) {
            return tr("msg.top.stellurgy.rocket.nuclear");
        }
        return tr("entity.stellurgy.rocket.name");
    }
}