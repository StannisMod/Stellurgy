package dev.stannismod.stellurgy.libvulpes.tile.multiblock;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.libvulpes.Configuration;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.ITimeModifier;
import dev.stannismod.stellurgy.libvulpes.api.IToggleableMachine;
import dev.stannismod.stellurgy.libvulpes.api.IUniversalEnergy;
import dev.stannismod.stellurgy.libvulpes.block.BlockMeta;
import dev.stannismod.stellurgy.libvulpes.block.BlockTile;
import dev.stannismod.stellurgy.libvulpes.client.RepeatingSound;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine.NetworkPackets;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.MultiBattery;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils;

import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;

public class TileMultiPowerConsumer extends TileMultiBlock implements INetworkMachine, IModularInventory, IProgressBar, IToggleButton, ITickable, IToggleableMachine {

	protected MultiBattery batteries = new MultiBattery();

	private float timeMultiplier;
	protected int completionTime, currentTime;
	protected int powerPerTick;
	protected boolean enabled;
	protected ModuleToggleSwitch toggleSwitch;
	//On server determines change in power state, on client determines last power state on server
	public boolean hadPowerLastTick;

	Object soundToPlay;

	public TileMultiPowerConsumer() {
		super();
		enabled = false;
		completionTime = -1;
		currentTime = -1;
		hadPowerLastTick = true;
		timeMultiplier = 1;
		toggleSwitch = new ModuleToggleSwitch(160, 5, 0, "", this,  dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonToggleImage, 11, 26, getMachineEnabled());
	}

	//Needed for GUI stuff
	public MultiBattery getBatteries() {
		return batteries;
	}

	public float getPowerMultiplier() {
		return Configuration.powerMult;
	}

	
	@Override
	public int getProgress(int id) {
		return currentTime;
	}

	@Override
	public int getTotalProgress(int id) {
		return completionTime;
	}

	@Override
	public void setProgress(int id, int progress) {
		currentTime = progress;
	}

	@Override
	public void setTotalProgress(int id, int progress) {
		completionTime = progress;
	}

	@Override
	public float getNormallizedProgress(int id) {

		return completionTime > 0 ? currentTime/(float)completionTime : 0f;
	}

	public SoundEvent getSound() {
		return null;
	}

	public int getSoundDuration() {
		return 1;
	}

	/**
	 * 
	 * @param state
	 * @param tile can be null
	 * @return
	 */
	public float getTimeMultiplierForBlock(IBlockState state, @Nullable TileEntity tile) {

		ItemStack droppedItem = new ItemStack(state.getBlock(), 1, state.getBlock().getMetaFromState(state));

		//Check for motors & such
		if(state.getBlock() instanceof ITimeModifier)
			return ((ITimeModifier)state.getBlock()).getTimeMult();
		//Check coils, but with compat so people can add IE coils if wanted
		else if(ZUtils.isItemInOreDict(droppedItem, "coilGold") || ZUtils.isItemInOreDict(droppedItem, "coilElectrum"))
			return 0.9f;
		else if(ZUtils.isItemInOreDict(droppedItem, "coilAluminum") || ZUtils.isItemInOreDict(droppedItem, "coilHighVoltage"))
			return 0.8f;
		else if(ZUtils.isItemInOreDict(droppedItem, "coilTitanium"))
			return 0.75f;
		else if(ZUtils.isItemInOreDict(droppedItem, "coilIridium"))
			return 0.5f;
		//Everything else is default
		return 1f;
	}

	public float getTimeMultiplier() {
		return timeMultiplier;
	}

	@Override
	protected void replaceStandardBlock(BlockPos newPos, IBlockState state,
			TileEntity tile) {
		super.replaceStandardBlock(newPos, state, tile);
		timeMultiplier *= getTimeMultiplierForBlock(state, tile);
	}
	
	@Override
	public void update() {

		//Freaky janky crap to make sure the multiblock loads on chunkload etc
		if(timeAlive == 0) {
			if(!world.isRemote) {
				if(isComplete())
					canRender = completeStructure = completeStructure(world.getBlockState(pos));
			}
			else {
				SoundEvent str;
				if((str = getSound()) != null) {
					playMachineSound(str);
				}
			}

			timeAlive = 0x1;
		}

		retryFormationIfDue();

		if(isRunning()) {
			if((!world.isRemote && hasEnergy(requiredPowerPerTick())) || (world.isRemote && hadPowerLastTick)) {

				onRunningPoweredTick();

				//If server then check to see if we need to update the client, use power and process output if applicable
				if(!world.isRemote) {

					if(!hadPowerLastTick) {
						hadPowerLastTick = true;
						markDirty();
						PacketHandler.sendToNearby(new PacketMachine(this, (byte)NetworkPackets.POWERERROR.ordinal()), this.world.provider.getDimension(), this.pos.getX(), this.pos.getY(), this.pos.getZ(), 256.0);
						world.notifyBlockUpdate(pos, world.getBlockState(pos),  world.getBlockState(pos), 3);
					}

				}
			}
			else if(!world.isRemote && hadPowerLastTick) { //If server and out of power check to see if client needs update
				hadPowerLastTick = false;
				markDirty();
				PacketHandler.sendToNearby(new PacketMachine(this, (byte)NetworkPackets.POWERERROR.ordinal()), this.world.provider.getDimension(), this.pos.getX(), this.pos.getY(), this.pos.getZ(), 256.0);
				world.notifyBlockUpdate(pos, world.getBlockState(pos),  world.getBlockState(pos), 3);
			}
		}
	}

	/**
	 * On the server, every {@link #FORMATION_RETRY_PERIOD_TICKS} of world time, re-validates a
	 * structure that is not complete. A part whose chunk unloads un-completes its controller and is
	 * not told when it comes back, so without this a machine stays dead until somebody right-clicks it.
	 */
	protected void retryFormationIfDue() {
		if(!world.isRemote && world.getTotalWorldTime() % FORMATION_RETRY_PERIOD_TICKS == 0 && !isComplete()) {
			attemptCompleteStructure(world.getBlockState(pos));
			markDirty();
			world.notifyBlockUpdate(pos, world.getBlockState(pos),  world.getBlockState(pos), 3);
		}
	}

	/** How often, in world ticks, an incomplete machine re-validates its structure on its own. */
	public static final long FORMATION_RETRY_PERIOD_TICKS = 1000L;

	/**
	 * @return amount of power to allow the machine to run this tick
	 */
	protected int requiredPowerPerTick() {
		return  (int) Math.max(powerPerTick*getPowerMultiplier(),1);
	}

	/**
	 * @return the amount of power actually used by the machine this tick
	 */
	protected int usedPowerPerTick() {
		return requiredPowerPerTick();
	}

	protected void onRunningPoweredTick() {

		if(!world.isRemote)
			useEnergy(usedPowerPerTick());
		//Increment for both client and server
		currentTime++;

		/*
		SoundEvent str;
		if(world.isRemote && (str = getSound()) != null && world.getTotalWorldTime() % getSoundDuration() == 0) {
			playMachineSound(str);
		}
		 */

		if(currentTime == completionTime)
			processComplete();
	}

	protected void playMachineSound(SoundEvent str) {
		//Screw you too

		if(soundToPlay == null && world.isRemote) {
			soundToPlay = new RepeatingSound(str, SoundCategory.BLOCKS, this);
		}

		LibVulpes.proxy.playSound(soundToPlay);
	}

	public void setMachineEnabled(boolean enabled) {
		this.enabled = enabled;
		if(!world.isRemote) {
			this.markDirty();
			world.notifyBlockUpdate(pos, world.getBlockState(pos),  world.getBlockState(pos), 3);
		}

	}

	public boolean getMachineEnabled() {
		return enabled;
	}

	public void resetCache() {
		batteries.clear();
		super.resetCache();
	}

	/**
	 * @param world world
	 * @param destroyedPos coords of destroyed block
	 * @param blockBroken set true if the block is being broken, otherwise some other means is being used to disassemble the machine
	 */
	@Override
	public void deconstructMultiBlock(World world, BlockPos destroyedPos, boolean blockBroken, IBlockState state) {
		resetCache();
		completionTime = 0;
		currentTime = 0;
		enabled = false;
		timeMultiplier = 1f;

		super.deconstructMultiBlock(world, destroyedPos, blockBroken, state);
	}

	protected void processComplete() {
		completionTime = 0;
		currentTime = 0;

		this.markDirty();
		world.notifyBlockUpdate(pos, world.getBlockState(pos),  world.getBlockState(pos), 3);
	}

	/**
	 * True if the machine is running
	 * @return true if the machine is currently processing something, or more formally, if completionTime > 0
	 */
	public boolean isRunning() {
		return completionTime > 0 && isComplete();
	}

	public void useEnergy(int amt) {
		batteries.extractEnergy(amt, false);
	}

	public boolean hasEnergy(int amt) {
		return batteries.getUniversalEnergyStored() >= amt;
	}

	@Override
	protected void integrateTile(TileEntity tile) {
		super.integrateTile(tile);

		for(BlockMeta block : TileMultiBlock.getMapping('P')) {
			if(block.getBlock() == world.getBlockState(tile.getPos()).getBlock())
				batteries.addBattery((IUniversalEnergy) tile);
		}
	}

	@Override
	protected void writeNetworkData(NBTTagCompound nbt) {
		super.writeNetworkData(nbt);
		nbt.setInteger("completionTime", this.completionTime);
		nbt.setInteger("currentTime", this.currentTime);
		nbt.setInteger("powerPerTick", this.powerPerTick);
		nbt.setBoolean("enabled", enabled);

		if(timeMultiplier != 1)
			nbt.setFloat("timeMult", timeMultiplier);
	}

	@Override
	protected void readNetworkData(NBTTagCompound nbt) {
		super.readNetworkData(nbt);
		completionTime = nbt.getInteger("completionTime");
		currentTime = nbt.getInteger("currentTime");
		powerPerTick = nbt.getInteger("powerPerTick");
		enabled = nbt.getBoolean("enabled");

		if(nbt.hasKey("timeMult"))
			timeMultiplier = nbt.getFloat("timeMult");

		if(world != null && world.isRemote && isRunning()) {
			((BlockTile)getBlockType()).setBlockState(world,world.getBlockState(getPos()), getPos(),true);
		}
	}

	@Override
	public void writeDataToNetwork(ByteBuf out, byte id) {

		if(id == NetworkPackets.POWERERROR.ordinal()) {
			out.writeBoolean(hadPowerLastTick);
		}
		else if(id == NetworkPackets.TOGGLE.ordinal()) {
			out.writeBoolean(enabled);
		}
	}

	@Override
	public void readDataFromNetwork(ByteBuf in, byte packetId,
			NBTTagCompound nbt) {
		if(packetId == NetworkPackets.POWERERROR.ordinal()) {
			nbt.setBoolean("hadPowerLastTick", in.readBoolean());
		}
		else if(packetId == NetworkPackets.TOGGLE.ordinal()) {
			nbt.setBoolean("enabled", in.readBoolean());
		}
	}

	@Override
	public void useNetworkData(EntityPlayer player, Side side, byte id,
			NBTTagCompound nbt) {

		if(id == NetworkPackets.POWERERROR.ordinal()) {
			hadPowerLastTick = nbt.getBoolean("hadPowerLastTick");
		} else if (id == NetworkPackets.TOGGLE.ordinal()) {
			setMachineEnabled(nbt.getBoolean("enabled"));
			toggleSwitch.setToggleState(getMachineEnabled());

			//Last ditch effort to update the toggle switch when it's flipped
			if(!world.isRemote)
				PacketHandler.sendToNearby(new PacketMachine(this, (byte)NetworkPackets.TOGGLE.ordinal()), world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 64);
		}
	}

	@Override
	public List<ModuleBase> getModules(int ID, EntityPlayer player) {
		LinkedList<ModuleBase> modules = new LinkedList<>();
		modules.add(new ModulePower(18, 20, getBatteries()));
		modules.add(toggleSwitch = new ModuleToggleSwitch(160, 5, 0, "", this,  dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonToggleImage, 11, 26, getMachineEnabled()));
		modules.add(new ModuleText(140,40,LibVulpes.proxy.getLocalizedString("msg.libvulpes.machine.speed") + "\n" + String.format("%.2fx", 1/getTimeMultiplier()), 0x2d2d2d));
		modules.add(new ModuleText(140,60,LibVulpes.proxy.getLocalizedString("msg.libvulpes.machine.power") + "\n" + String.format("%.2fx", 1f), 0x2d2d2d));
		
		return modules;
	}

	@Override
	public String getModularInventoryName() {
		return getMachineName();
	}

	@Override
	public void onInventoryButtonPressed(int buttonId) {
		if(buttonId == 0) {
			this.setMachineEnabled(toggleSwitch.getState());
			PacketHandler.sendToServer(new PacketMachine(this,(byte)TileMultiblockMachine.NetworkPackets.TOGGLE.ordinal()));
		}
	}

	@Override
	public void stateUpdated(ModuleBase module) {
		if(module == toggleSwitch)
			setMachineEnabled(toggleSwitch.getState());
	}

	@Override
	public boolean canInteractWithContainer(EntityPlayer entity) {
		return isComplete();
	}
}
