package dev.stannismod.stellurgy.libvulpes.inventory.modules;

public interface ISelectionNotify {
	void onSelected(Object sender);
	
	void onSelectionConfirmed(Object sender);

	void onSystemFocusChanged(Object sender);
}
