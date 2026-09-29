package dev.stannismod.stellurgy.libvulpes.interfaces;

import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;

public interface INetworkEntity extends INetworkMachine {
	
	//Cannot overwrite Entity
	int getEntityId();
}
