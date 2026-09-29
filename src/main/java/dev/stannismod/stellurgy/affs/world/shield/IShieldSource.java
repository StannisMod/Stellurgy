package dev.stannismod.stellurgy.affs.world.shield;

public interface IShieldSource extends IShieldNetworkNode {

    int getAvailableShieldEnergy();

    int extractShieldEnergy(int amount);
}
