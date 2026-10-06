package dev.stannismod.stellurgy.libvulpes.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;


public interface INetworkMachine {

	//Writes data to the network given an id of what type of packet to write
	void writeDataToNetwork(ByteBuf out, byte id);

	//Reads data, stores read data to nbt to be passed to useNetworkData
	void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt);

	//Applies changes from network
	void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt);

	/**
	 * Whether {@code player} may use this machine now. A packet a client sends to a machine is that
	 * player using it, so the server reads nothing from the packet unless this answers {@code true};
	 * a machine with a screen answers its container question with the same predicate, because an open
	 * screen is the player using the machine. Usually {@link MachineReach}.
	 *
	 * <p>Not {@code IInventory#isUsableByPlayer}, though the question sounds the same: sharing that
	 * signature would let whatever a machine's inventory answers — often a bare {@code true} — answer
	 * this too, without anyone having decided it.</p>
	 */
	boolean canBeUsedBy(EntityPlayer player);
}
