package dev.stannismod.stellurgy.affs.world.shield;

public interface IShieldCable extends IShieldNetworkNode {

    int getThroughputPerTick();

    void addTransferredShield(int amount);
}
