package dev.stannismod.stellurgy.affs.world.shield;

public interface IShieldNetworkController extends IShieldNetworkNode {

    double getShieldEnergyResistanceBias();

    void applyNetworkState(ShieldNetworkState state);
}
