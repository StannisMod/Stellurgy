package dev.stannismod.stellurgy.libvulpes.tile.energy;

/**
 * A multiblock's power input. It is reached from the world only through its Forge Energy capability,
 * never as an {@code IEnergyStorage} itself: that interface's {@code extractEnergy} is the same method
 * the machine draws through, and it could not refuse an outside caller without refusing the machine.
 */
public class TileForgePowerInput extends TilePlugBase {

	@Override
	public String getModularInventoryName() {
		return "tile.forgePowerInput.name";
	}

	@Override
	public String getName() {
		return "";
	}

	@Override
	public boolean canExtract() {
		return false;
	}

	@Override
	public boolean canReceive() {
		return true;
	}

}
