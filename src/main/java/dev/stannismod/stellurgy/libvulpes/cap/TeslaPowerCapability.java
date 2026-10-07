package dev.stannismod.stellurgy.libvulpes.cap;

import net.darkhax.tesla.api.ITeslaConsumer;
import net.darkhax.tesla.api.ITeslaHolder;
import net.darkhax.tesla.api.ITeslaProducer;
import dev.stannismod.stellurgy.libvulpes.api.IUniversalEnergy;

public class TeslaPowerCapability implements ITeslaConsumer, ITeslaProducer, ITeslaHolder {
	
	IUniversalEnergy energy;
	
	public TeslaPowerCapability(IUniversalEnergy energy) {
		this.energy = energy;
	}

	@Override
	public long getCapacity() {
		return energy.getMaxEnergyStored();
	}

	@Override
	public long getStoredPower() {
		return energy.getUniversalEnergyStored();
	}

	/**
	 * The two transfers are the world's way in, so they answer only in the directions the store
	 * declares, as the Forge capability does; the store's own owner moves energy the other way through
	 * the store itself.
	 */
	@Override
	public long takePower(long amt, boolean simulate) {
		if(!energy.canExtract())
			return 0;
		return energy.extractEnergy((int)amt, simulate);
	}

	@Override
	public long givePower(long amt, boolean simulate) {
		if(!energy.canReceive())
			return 0;
		return energy.acceptEnergy((int)amt, simulate);
	}
}
