package dev.stannismod.stellurgy.libvulpes.compat;

import dev.stannismod.stellurgy.libvulpes.Configuration;
import dev.stannismod.stellurgy.libvulpes.tile.energy.TilePlugInputGregTech;
import dev.stannismod.stellurgy.libvulpes.util.UniversalBattery;
import net.minecraft.util.EnumFacing;

public class GTEnergyCapability implements gregtech.api.capability.IEnergyContainer {

	UniversalBattery storage;
	private final TilePlugInputGregTech plug;

	public GTEnergyCapability(UniversalBattery storage, TilePlugInputGregTech plug)
	{
		this.storage = storage;
		this.plug = plug;
	}

	@Override
	public long getEnergyStored() {
		return storage.getUniversalEnergyStored();
	}

	/**
	 * GT's network offers {@code amperage} packets of {@code voltage} EU each and counts the answer
	 * as AMPERES used, so it is answered in packets: as many whole packets as the plug has room for,
	 * within the amperage it declares per tick, each stored as its EU times {@code EUPowerMultiplier}.
	 */
	@Override
	public long acceptEnergyFromNetwork(EnumFacing side, long voltage, long amperage) {
		if (voltage <= 0 || amperage <= 0)
			return 0;
		long fePerPacket = (long) (voltage * Configuration.EUMult);
		if (fePerPacket <= 0)
			return 0;
		long room = (long) storage.getMaxEnergyStored() - storage.getUniversalEnergyStored();
		long packets = Math.min(Math.min(amperage, room / fePerPacket),
				plug.amperesLeftThisTick(getInputAmperage()));
		if (packets <= 0)
			return 0;
		storage.acceptEnergy((int) (packets * fePerPacket), false);
		plug.useAmperes(packets);
		return packets;
	}

	@Override
	public long changeEnergy(long arg0) {
		if (arg0 < 0)
			return storage.extractEnergy((int) -arg0, false);
		else
			return storage.acceptEnergy((int) arg0, false);
	}

	@Override
	public long getEnergyCapacity() {
		return storage.getMaxEnergyStored();
	}

	@Override
	public long getInputAmperage() {
		return 1;
	}

	@Override
	public long getInputVoltage() {
		return 32;
	}

	@Override
	public boolean inputsEnergy(EnumFacing arg0) {
		return true;
	}
}
