package dev.stannismod.stellurgy.libvulpes.inventory.modules;

public interface IToggleButton extends IButtonInventory {
	/**
	 * Called when a module is toggled
	 * @param module module that was toggled
	 */
	void stateUpdated(ModuleBase module);
}
