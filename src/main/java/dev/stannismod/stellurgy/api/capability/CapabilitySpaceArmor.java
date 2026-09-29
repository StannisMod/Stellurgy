package dev.stannismod.stellurgy.api.capability;

import net.minecraft.nbt.NBTBase;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import dev.stannismod.stellurgy.api.armor.IProtectiveArmor;

public class CapabilitySpaceArmor {

    @CapabilityInject(IProtectiveArmor.class)
    public static Capability<IProtectiveArmor> PROTECTIVEARMOR = null;

    public CapabilitySpaceArmor() {
    }


    public static void register() {
        CapabilityManager.INSTANCE.register(IProtectiveArmor.class, new Capability.IStorage<IProtectiveArmor>() {
            @Override
            public void readNBT(Capability<IProtectiveArmor> capability,
                                IProtectiveArmor instance, EnumFacing side, NBTBase nbt) {

            }

            @Override
            public NBTBase writeNBT(
                    Capability<IProtectiveArmor> capability,
                    IProtectiveArmor instance, EnumFacing side) {
                return null;
            }
        }, ((IProtectiveArmor) (atmosphere, stack, commitProtection) -> false).getClass());
    }

}
