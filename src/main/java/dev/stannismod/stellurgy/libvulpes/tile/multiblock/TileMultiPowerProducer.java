package dev.stannismod.stellurgy.libvulpes.tile.multiblock;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.libvulpes.api.IUniversalEnergy;
import dev.stannismod.stellurgy.libvulpes.block.BlockMeta;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine.NetworkPackets;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.MultiBattery;

import java.util.LinkedList;
import java.util.List;

public class TileMultiPowerProducer extends TileMultiBlock implements IToggleButton, IModularInventory, INetworkMachine {

	protected MultiBattery batteries = new MultiBattery();
	protected boolean enabled;
	private ModuleToggleSwitch toggleSwitch;

	public TileMultiPowerProducer() {
		super();
		enabled = false;
		toggleSwitch = new ModuleToggleSwitch(160, 5, 0, "", this,  dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonToggleImage, 11, 26, getMachineEnabled());
	}

	public boolean getMachineEnabled() {
		return enabled;
	}

	public void setMachineEnabled(boolean enabled) {
		this.enabled = enabled;
		this.markDirty();
		world.notifyBlockUpdate(pos, world.getBlockState(pos),  world.getBlockState(pos), 3);
	}

	@Override
	public void useNetworkData(EntityPlayer player, Side side, byte id,
			NBTTagCompound nbt) {
		if (id == NetworkPackets.TOGGLE.ordinal()) {
			setMachineEnabled(nbt.getBoolean("enabled"));
			toggleSwitch.setToggleState(getMachineEnabled());

			//Last ditch effort to update the toggle switch when it's flipped
			if(!world.isRemote)
				PacketHandler.sendToNearby(new PacketMachine(this, (byte)NetworkPackets.TOGGLE.ordinal()), world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 64);
		}
	}
	
	@Override
	public void writeDataToNetwork(ByteBuf out, byte id) {
		if(id == NetworkPackets.TOGGLE.ordinal()) {
			out.writeBoolean(enabled);
		}
	}

	@Override
	public void readDataFromNetwork(ByteBuf in, byte packetId,
			NBTTagCompound nbt) {
		if(packetId == NetworkPackets.TOGGLE.ordinal()) {
			nbt.setBoolean("enabled", in.readBoolean());
		}
	}

	@Override
	public void onInventoryButtonPressed(int buttonId) {

		if(buttonId == 0) {
			this.setMachineEnabled(toggleSwitch.getState());
			PacketHandler.sendToServer(new PacketMachine(this,(byte)TileMultiblockMachine.NetworkPackets.TOGGLE.ordinal()));
		}
	}
	
	public MultiBattery getBatteries() {
		return batteries;
	}

	@Override
	public void stateUpdated(ModuleBase module) {
		if(module == toggleSwitch)
			setMachineEnabled(toggleSwitch.getState());
	}

	@Override
	public List<ModuleBase> getModules(int ID, EntityPlayer player) {
		LinkedList<ModuleBase> modules = new LinkedList<>();
		modules.add(new ModulePower(18, 20, getBatteries()));
		modules.add(toggleSwitch = new ModuleToggleSwitch(160, 5, 0, "", this,  dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonToggleImage, 11, 26, getMachineEnabled()));

		return modules;
	}
	
	/**
	 * Handles distributing power production
	 */
	public void producePower(int amt) {
		batteries.acceptEnergy(amt, false);
	}
	
	@Override
	public String getModularInventoryName() {
		return getMachineName();
	}
	
	@Override
	public boolean canInteractWithContainer(EntityPlayer entity) {
		return isComplete();
	}
	
	public void resetCache() {
		batteries.clear();
		super.resetCache();
	}
	
	protected void integrateTile(TileEntity tile) {
		super.integrateTile(tile);

		for(BlockMeta block : TileMultiBlock.getMapping('p')) {
			if(block.getBlock() == world.getBlockState(tile.getPos()).getBlock())
				batteries.addBattery((IUniversalEnergy) tile);
		}
	}

	@Override
	protected void writeNetworkData(NBTTagCompound nbt) {
		super.writeNetworkData(nbt);
		nbt.setBoolean("enabled", enabled);
	}
	
	@Override
	protected void readNetworkData(NBTTagCompound nbt) {
		super.readNetworkData(nbt);
		enabled = nbt.getBoolean("enabled");
	}
}
