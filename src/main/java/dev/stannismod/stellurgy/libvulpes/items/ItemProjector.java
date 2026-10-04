package dev.stannismod.stellurgy.libvulpes.items;

import com.mojang.realmsclient.gui.ChatFormatting;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.block.BlockMeta;
import dev.stannismod.stellurgy.libvulpes.block.BlockTile;
import dev.stannismod.stellurgy.libvulpes.block.multiblock.BlockMultiblockMachine;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiHandler;
import dev.stannismod.stellurgy.libvulpes.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.INetworkItem;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketItemModifcation;
import dev.stannismod.stellurgy.libvulpes.tile.TileSchematic;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiBlock;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TilePlaceholder;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.Vector3F;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.Map.Entry;
import dev.stannismod.stellurgy.api.Constants;

public class ItemProjector extends Item implements IModularInventory, IButtonInventory, INetworkItem {

	private ArrayList<TileMultiBlock> machineList;
	private ArrayList<BlockTile> blockList;
	private ArrayList<String> descriptionList;

	private static final String IDNAME = "machineId";

	private static final int BUTTON_COLOR_NORMAL = 0xFF22FF22;
	private static final int BUTTON_COLOR_SELECTED = 0xFFFFFF55;
	private static final int BUTTON_BG_NORMAL = 0xFFFFFFFF;
	private static final int BUTTON_BG_SELECTED = 0xFF444444;

	private static final int PROJECTOR_BUTTON_X = 60;
	private static final int PROJECTOR_BUTTON_START_Y = 4;
	private static final int PROJECTOR_BUTTON_SPACING_Y = 24;

	private static final int PROJECTOR_PAN_X = 5;
	private static final int PROJECTOR_PAN_Y = 38;
	private static final int PROJECTOR_PAN_WIDTH = 160;
	private static final int PROJECTOR_PAN_HEIGHT = 64;

	public ItemProjector() {
		machineList = new ArrayList<>();
		blockList = new ArrayList<>();
		descriptionList = new ArrayList<>();
	}

	public void registerMachine(TileMultiBlock multiblock, BlockTile mainBlock) {
		machineList.add(multiblock);
		blockList.add(mainBlock);
		HashMap<Object, Integer> map = new HashMap<>();

		Object[][][] structure = multiblock.getStructure();

		for (Object[][] objects2d : structure) {
			for (Object[] objects : objects2d) {
				for (Object object : objects) {
					if (!map.containsKey(object)) {
						map.put(object, 1);
					} else
						map.put(object, map.get(object) + 1);
				}
			}
		}

		String orSeparator = " " + LibVulpes.proxy.getLocalizedString("msg.libvulpes.holoProjector.or") + " ";
		StringBuilder str = new StringBuilder(Item.getItemFromBlock(mainBlock).getItemStackDisplayName(new ItemStack(mainBlock)) + " x1\n");

		for(Entry<Object, Integer> entry : map.entrySet()) {

			List<BlockMeta> blockMeta = multiblock.getAllowableBlocks(entry.getKey());

			if(blockMeta.isEmpty() || Item.getItemFromBlock(blockMeta.get(0).getBlock()) == Items.AIR || blockMeta.get(0).getBlock() == Blocks.AIR )
				continue;
			for (BlockMeta meta : blockMeta) {
				String itemStr = Item.getItemFromBlock(meta.getBlock()).getItemStackDisplayName(new ItemStack(meta.getBlock(), 1, meta.getMeta()));
				if (!itemStr.contains("tile.")) {
					str.append(itemStr);
					str.append(orSeparator);
				}
			}

			if(str.toString().endsWith(orSeparator)) {
				str = new StringBuilder(str.substring(0, str.length() - orSeparator.length()));
			}
			str.append(" x").append(entry.getValue()).append("\n");
		}

		descriptionList.add(str.toString());
	}

	@SubscribeEvent
	@SideOnly(Side.CLIENT)
	public void mouseEvent(MouseEvent event) {
		if(Minecraft.getMinecraft().player.isSneaking() && event.getDwheel() != 0) {
			ItemStack stack = Minecraft.getMinecraft().player.getHeldItem(EnumHand.MAIN_HAND);

			if(!stack.isEmpty() && stack.getItem() == this && getMachineId(stack) != -1) {
				if(event.getDwheel() < 0) {
					setYLevel(stack, getYLevel(stack) + 1);
				}
				else
					setYLevel(stack, getYLevel(stack) - 1);
				event.setCanceled(true);

				PacketHandler.sendToServer(new PacketItemModifcation(this, Minecraft.getMinecraft().player, (byte)1));
			}
		}
	}

	private void clearStructure(World world, TileMultiBlock tile, @Nonnull ItemStack stack) {

		int id = getMachineId(stack);
		EnumFacing direction = EnumFacing.getFront(getDirection(stack));

		TileMultiBlock multiblock = machineList.get(id);

		int prevMachineId = getPrevMachineId(stack);
		Object[][][] structure;
		if(prevMachineId >= 0 && prevMachineId < machineList.size()) {
			structure = machineList.get(prevMachineId).getStructure();

			Vector3F<Integer> basepos = getBasePosition(stack);

			for(int y = 0; y < structure.length; y++) {
				for(int z=0 ; z < structure[0].length; z++) {
					for(int x=0; x < structure[0][0].length; x++) {

						int globalX = basepos.x - x*direction.getFrontOffsetZ() + z*direction.getFrontOffsetX();
						int globalZ = basepos.z + (x* direction.getFrontOffsetX()) + (z*direction.getFrontOffsetZ());
						BlockPos pos = new BlockPos(globalX, basepos.y + y, globalZ);
						if(world.getBlockState(pos).getBlock() == LibVulpesBlocks.blockPhantom) 
							world.setBlockToAir(pos);
					}
				}
			}
		}
	}

	private void RebuildStructure(World world, TileMultiBlock tile, @Nonnull ItemStack stack, int posX, int posY, int posZ, EnumFacing orientation) {

		int id = getMachineId(stack);
		EnumFacing direction = EnumFacing.getFront(getDirection(stack));

		TileMultiBlock multiblock = machineList.get(id);
		Object[][][] structure;

		clearStructure(world, tile, stack);

		structure = multiblock.getStructure();
		direction = orientation;

		int y = getYLevel(stack);
		int endNumber, startNumber;

		if(y == -1) {
			startNumber = 0;
			endNumber = structure.length;
		}
		else {
			startNumber = y;
			endNumber = y + 1;
		}
		for(y=startNumber; y < endNumber; y++) {
			for(int z=0 ; z < structure[0].length; z++) {
				for(int x=0; x < structure[0][0].length; x++) {
					List<BlockMeta> block;
					if(structure[y][z][x] instanceof Character && (Character)structure[y][z][x] == 'c') {
						block = new ArrayList<>();
						block.add(new BlockMeta(blockList.get(id), orientation.getOpposite().ordinal()));
					}
					else if(multiblock.getAllowableBlocks(structure[y][z][x]).isEmpty())
						continue;
					else
						block = multiblock.getAllowableBlocks(structure[y][z][x]);

					int globalX = posX - x*direction.getFrontOffsetZ() + z*direction.getFrontOffsetX();
					int globalZ = posZ + (x* direction.getFrontOffsetX())  + (z*direction.getFrontOffsetZ());
					int globalY = -y + structure.length + posY - 1;
					BlockPos pos = new BlockPos(globalX, globalY, globalZ);

					if((world.isAirBlock(pos) || world.getBlockState(pos).getBlock().isReplaceable(world, pos)) && block.get(0).getBlock() != Blocks.AIR) {
						//block = (Block)structure[y][z][x];
						world.setBlockState(pos,  LibVulpesBlocks.blockPhantom.getStateFromMeta(block.get(0).getMeta()));
						TileEntity newTile = world.getTileEntity(pos);

						//TODO: compatibility fixes with the tile entity not reflecting current block
						if(newTile instanceof TilePlaceholder) {
							((TileSchematic)newTile).setReplacedBlock(block);

							((TilePlaceholder)newTile).setReplacedTileEntity(block.get(0).getBlock().createTileEntity(world, block.get(0).getBlock().getDefaultState()));
						}
					}
				}
			}
		}
		this.setPrevMachineId(stack, id);
		this.setBasePosition(stack, posX, posY, posZ);
		this.setDirection(stack, orientation.ordinal());
	}
	
	@Override
	@Nonnull
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
		
		if( player.isSneaking()) {
			if(!world.isRemote)
				player.openGui(Constants.modId, GuiHandler.guiId.MODULARNOINV.ordinal(), world, -1, -1, 0);
			return super.onItemRightClick(world, player, hand);
		}
		return super.onItemRightClick(world, player, hand);
	}

	@Override
	@Nonnull
	public EnumActionResult onItemUseFirst(EntityPlayer player, World world,
			BlockPos blockPos, EnumFacing side, float hitX, float hitY, float hitZ,
			EnumHand hand) {


		ItemStack stack = player.getHeldItem(hand);
		
		int id = getMachineId(stack);
		if(!player.isSneaking() && id != -1 && world.isRemote) {
			EnumFacing dir = EnumFacing.getFront(ZUtils.getDirectionFacing(player.rotationYaw - 180));
			TileMultiBlock tile = machineList.get(getMachineId(stack));


			int x = tile.getStructure()[0][0].length;
			int z = tile.getStructure()[0].length;

			int globalX = (-x*dir.getFrontOffsetZ() + z*dir.getFrontOffsetX())/2;
			int globalZ = ((x* dir.getFrontOffsetX())  + (z*dir.getFrontOffsetZ()))/2;

			RayTraceResult pos = Minecraft.getMinecraft().objectMouseOver;

			TileEntity tile2;
			if((tile2 = world.getTileEntity(pos.getBlockPos())) instanceof TileMultiBlock) {
				for(TileMultiBlock tiles:  machineList) {
					if(tile2.getClass() == tiles.getClass()) {

						setMachineId(stack, machineList.indexOf(tiles));
						Object[][][] structure = tiles.getStructure();

						HashedBlockPosition controller = getControllerOffset(structure);
						dir = BlockMultiblockMachine.getFront(world.getBlockState(tile2.getPos())).getOpposite();

						controller.y = (short) (structure.length - controller.y);

						globalX = (-controller.x*dir.getFrontOffsetZ() + controller.z*dir.getFrontOffsetX());
						globalZ = ((controller.x* dir.getFrontOffsetX())  + (controller.z*dir.getFrontOffsetZ()));

						setDirection(stack, dir.ordinal());

						setBasePosition(stack, pos.getBlockPos().getX() - globalX, pos.getBlockPos().getY() - controller.y  + 1, pos.getBlockPos().getZ() - globalZ);
						PacketHandler.sendToServer(new PacketItemModifcation(this, player, (byte)0));
						PacketHandler.sendToServer(new PacketItemModifcation(this, player, (byte)2));
						return super.onItemUseFirst(player, world, blockPos, side, hitX, hitY, hitZ, hand);
					}
				}
			}

			if(pos.sideHit == EnumFacing.DOWN)
				setBasePosition(stack, pos.getBlockPos().getX() - globalX, pos.getBlockPos().getY() - tile.getStructure().length, pos.getBlockPos().getZ() - globalZ);
			else
				setBasePosition(stack, pos.getBlockPos().getX() - globalX, pos.getBlockPos().getY()+1, pos.getBlockPos().getZ() - globalZ);
			setDirection(stack, dir.ordinal());

			PacketHandler.sendToServer(new PacketItemModifcation(this, player, (byte)2));
		}

		return super.onItemUseFirst(player, world, blockPos, side, hitX, hitY, hitZ, hand);
	}

	protected HashedBlockPosition getControllerOffset(Object[][][] structure) {
		for(int y = 0; y < structure.length; y++) {
			for(int z = 0; z < structure[0].length; z++) {
				for(int x = 0; x< structure[0][0].length; x++) {
					if(structure[y][z][x] instanceof Character && (Character)structure[y][z][x] == 'c')
						return new HashedBlockPosition(x, y, z);
				}
			}
		}
		return null;
	}

	@Override
	public List<ModuleBase> getModules(int ID, EntityPlayer player) {
		List<ModuleBase> modules = new LinkedList<>();
		List<ModuleBase> btns = new LinkedList<>();

		boolean isClient = player != null && player.world.isRemote;
		// The search is the open screen's: one per opening, client side only. The item is one object
		// for the whole process, so state kept on it was shared by every projector screen, and the
		// server-side call of this method wiped what the client screen had just built.
		ProjectorSearch search = isClient ? new ProjectorSearch() : null;

		if(isClient) {
			modules.add(new ModuleText(
					8,
					23,
					LibVulpes.proxy.getLocalizedString("msg.libvulpes.holoProjector.search"),
					0x404040
			));

			search.box = new ModuleTextBox(search, 55, 18, 110, 14, 64);
			modules.add(search.box);
		}

		List<Integer> sortedMachineIds = new ArrayList<>();
		for(int i = 0; i < machineList.size(); i++) {
			sortedMachineIds.add(i);
		}

		Collections.sort(sortedMachineIds, new Comparator<Integer>() {
			@Override
			public int compare(Integer first, Integer second) {
				String firstName = LibVulpes.proxy.getLocalizedString(machineList.get(first).getMachineName());
				String secondName = LibVulpes.proxy.getLocalizedString(machineList.get(second).getMachineName());
				return firstName.compareToIgnoreCase(secondName);
			}
		});

		int row = 0;
		for(Integer machineId : sortedMachineIds) {
			TileMultiBlock multiblock = machineList.get(machineId);
			String machineName = LibVulpes.proxy.getLocalizedString(multiblock.getMachineName());

			if(isClient) {
				ModuleSelectableProjectorButton button = new ModuleSelectableProjectorButton(
						PROJECTOR_BUTTON_X,
						PROJECTOR_BUTTON_START_Y + row * PROJECTOR_BUTTON_SPACING_Y,
						machineId,
						machineName,
						this,
						dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonBuild
				);

				search.buttons.add(button);
				btns.add(button);
			}
			else {
				btns.add(new ModuleButton(
						PROJECTOR_BUTTON_X,
						PROJECTOR_BUTTON_START_Y + row * PROJECTOR_BUTTON_SPACING_Y,
						machineId,
						machineName,
						this,
						dev.stannismod.stellurgy.libvulpes.inventory.TextureResources.buttonBuild
				));
			}

			row++;
		}

		ModuleContainerPan panningContainer = new ModuleContainerPan(
				PROJECTOR_PAN_X,
				PROJECTOR_PAN_Y,
				btns,
				new LinkedList<>(),
				TextureResources.starryBG,
				PROJECTOR_PAN_WIDTH,
				PROJECTOR_PAN_HEIGHT,
				0,
				500
		);

		if(isClient) {
			search.pan = panningContainer;
			search.applyFilter();
		}

		modules.add(panningContainer);
		return modules;
	}

	@Override
	public String getModularInventoryName() {
		return "item.holoProjector.name";
	}

	@Override
	public boolean canInteractWithContainer(EntityPlayer entity) {
		return entity != null && !entity.isDead && !entity.getHeldItem(EnumHand.MAIN_HAND).isEmpty() && entity.getHeldItem(EnumHand.MAIN_HAND).getItem() == this;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void onInventoryButtonPressed(int buttonId) {
		ItemStack stack = Minecraft.getMinecraft().player.getHeldItem(EnumHand.MAIN_HAND);

		if(!stack.isEmpty() && stack.getItem() == this && buttonId >= 0 && buttonId < machineList.size()) {
			int oldId = getMachineId(stack);

			setMachineId(stack, buttonId);

			String machineName = LibVulpes.proxy.getLocalizedString(machineList.get(buttonId).getMachineName());

			if(oldId == buttonId) {
				Minecraft.getMinecraft().player.sendStatusMessage(
						new TextComponentTranslation("msg.libvulpes.holoProjector.alreadySelected", machineName),
						true
				);
			}
			else {
				Minecraft.getMinecraft().player.sendStatusMessage(
						new TextComponentTranslation("msg.libvulpes.holoProjector.selected", machineName),
						true
				);
			}

			PacketHandler.sendToServer(new PacketItemModifcation(this, Minecraft.getMinecraft().player, (byte)0));
		}
	}

	private String normalizeProjectorSearch(String text) {
		if(text == null) {
			return "";
		}

		return text.trim().toLowerCase(Locale.ROOT);
	}

	/** The machine search of one open projector screen: its text box, its pan and its buttons. */
	private class ProjectorSearch implements IGuiCallback {
		private ModuleTextBox box;
		private ModuleContainerPan pan;
		private final List<ModuleSelectableProjectorButton> buttons = new LinkedList<>();

		@Override
		public void onModuleUpdated(ModuleBase module) {
			if(module == box) {
				if(pan != null) {
					pan.setOffset2(0, 0);
				}
				applyFilter();
			}
		}

		private void applyFilter() {
			String search = normalizeProjectorSearch(box == null ? "" : box.getText());
			int visibleIndex = 0;

			for(ModuleSelectableProjectorButton button : buttons) {
				boolean matches = search.isEmpty() || normalizeProjectorSearch(button.getSearchText()).contains(search);

				button.setVisible(matches);
				button.setEnabled(matches);

				if(matches) {
					setProjectorButtonPosition(
							button,
							PROJECTOR_PAN_X + PROJECTOR_BUTTON_X,
							PROJECTOR_PAN_Y + PROJECTOR_BUTTON_START_Y + visibleIndex * PROJECTOR_BUTTON_SPACING_Y
					);

					visibleIndex++;
				}
			}
		}
	}

	private void setProjectorButtonPosition(ModuleSelectableProjectorButton button, int x, int y) {
		int deltaX = x - button.offsetX;
		int deltaY = y - button.offsetY;

		button.offsetX = x;
		button.offsetY = y;

		if(button.button != null) {
			button.button.x += deltaX;
			button.button.y += deltaY;
		}
	}

	@SideOnly(Side.CLIENT)
	private class ModuleSelectableProjectorButton extends ModuleButton {

		private final String searchText;

		public ModuleSelectableProjectorButton(int offsetX, int offsetY, int buttonId, String text, IButtonInventory tile, ResourceLocation[] buttonImages) {
			super(offsetX, offsetY, buttonId, text, tile, buttonImages);
			this.searchText = text;
		}

		public String getSearchText() {
			return searchText;
		}

		@Override
		@SideOnly(Side.CLIENT)
		public void renderForeground(int guiOffsetX, int guiOffsetY, int mouseX, int mouseY, float zLevel, GuiContainer gui, FontRenderer font) {
			if(button != null && !button.visible) {
				return;
			}

			EntityPlayer player = Minecraft.getMinecraft().player;
			boolean selected = false;

			if(player != null) {
				ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);
				selected = !stack.isEmpty() && stack.getItem() == ItemProjector.this && getMachineId(stack) == buttonId;
			}

			setColor(selected ? BUTTON_COLOR_SELECTED : BUTTON_COLOR_NORMAL);
			setBGColor(selected ? BUTTON_BG_SELECTED : BUTTON_BG_NORMAL);

			super.renderForeground(guiOffsetX, guiOffsetY, mouseX, mouseY, zLevel, gui, font);
		}
	}

	private void setMachineId(@Nonnull ItemStack stack, int id) {
		NBTTagCompound nbt;
		if(stack.hasTagCompound()) {
			nbt = stack.getTagCompound();
		}
		else 
			nbt = new NBTTagCompound();

		nbt.setInteger(IDNAME, id);
		stack.setTagCompound(nbt);
	}

	private int getMachineId(@Nonnull ItemStack stack) {
		if(stack.hasTagCompound() && stack.getTagCompound().hasKey(IDNAME)) {
			return stack.getTagCompound().getInteger(IDNAME);
		}
		else
			return -1;
	}

	private void setYLevel(@Nonnull ItemStack stack, int level) {
		NBTTagCompound nbt;
		if(stack.hasTagCompound()) {
			nbt = stack.getTagCompound();
		}
		else 
			nbt = new NBTTagCompound();

		TileMultiBlock machine = machineList.get(getMachineId(stack));

		if(level == -2)
			level = machine.getStructure().length-1;
		else if(level == machine.getStructure().length)
			level = -1;
		nbt.setInteger("yOffset", level);
		stack.setTagCompound(nbt);
	}

	private int getYLevel(@Nonnull ItemStack stack) {
		if(stack.hasTagCompound()) {
			return stack.getTagCompound().getInteger("yOffset");
		}
		else
			return -1;
	}

	private void setPrevMachineId(@Nonnull ItemStack stack, int id) {
		NBTTagCompound nbt;
		if(stack.hasTagCompound()) {
			nbt = stack.getTagCompound();
		}
		else 
			nbt = new NBTTagCompound();

		nbt.setInteger(IDNAME + "Prev", id);
		stack.setTagCompound(nbt);
	}

	private int getPrevMachineId(@Nonnull ItemStack stack) {
		if(stack.hasTagCompound()) {
			return stack.getTagCompound().getInteger(IDNAME + "Prev");
		}
		else
			return -1;
	}

	private Vector3F<Integer> getBasePosition(@Nonnull ItemStack stack) {
		if(stack.hasTagCompound()) {
			NBTTagCompound nbt = stack.getTagCompound();
			return new Vector3F<>(nbt.getInteger("x"), nbt.getInteger("y"), nbt.getInteger("z"));
		}
		else
			return null;
	}

	private void setBasePosition(@Nonnull ItemStack stack, int x, int y, int z) {
		NBTTagCompound nbt;
		if(stack.hasTagCompound()) {
			nbt = stack.getTagCompound();
		}
		else
			nbt = new NBTTagCompound();

		nbt.setInteger("x", x);
		nbt.setInteger("y", y);
		nbt.setInteger("z", z);

		stack.setTagCompound(nbt);
	}

	public int getDirection(@Nonnull ItemStack stack) {
		if(stack.hasTagCompound()) {
			return stack.getTagCompound().getInteger("dir");
		}
		else
			return -1;
	}

	public void setDirection(@Nonnull ItemStack stack, int dir) {
		NBTTagCompound nbt;
		if(stack.hasTagCompound()) {
			nbt = stack.getTagCompound();
		}
		else
			nbt = new NBTTagCompound();

		nbt.setInteger("dir", dir);

		stack.setTagCompound(nbt);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void addInformation(@Nonnull ItemStack stack, World player,
			List<String> list, ITooltipFlag bool) {
		super.addInformation(stack, player, list, bool);

		list.add(LibVulpes.proxy.getLocalizedString("msg.libvulpes.holoProjector.tooltip.openGui"));
		list.add(LibVulpes.proxy.getLocalizedString("msg.libvulpes.holoProjector.tooltip.crossSection"));

		int id = getMachineId(stack);
		if(id != -1) {
			list.add("");
			list.add(ChatFormatting.GREEN + LibVulpes.proxy.getLocalizedString(machineList.get(id).getMachineName()));
			String str = descriptionList.get(id);

			String[] strList = str.split("\n");

			list.addAll(Arrays.asList(strList));
		}
	}

	@Override
	public void writeDataToNetwork(ByteBuf out, byte id, @Nonnull ItemStack stack) {
		if(id == 0) {
			out.writeInt(getMachineId(stack));
		}
		else if(id == 1)
			out.writeInt(getYLevel(stack));
		else if(id == 2) {

			Vector3F<Integer> pos = getBasePosition(stack);
			out.writeInt(pos.x);
			out.writeInt(pos.y);
			out.writeInt(pos.z);
			out.writeInt(getDirection(stack));
		}
	}

	@Override
	public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt, @Nonnull ItemStack stack) {
		if(packetId == 0) {
			nbt.setInteger(IDNAME, in.readInt());
		}
		else if(packetId == 1)
			nbt.setInteger("yLevel", in.readInt());
		else if(packetId == 2) {
			nbt.setInteger("x", in.readInt());
			nbt.setInteger("y", in.readInt());
			nbt.setInteger("z", in.readInt());
			nbt.setInteger("dir", in.readInt());
		}
	}

	@Override
	public void useNetworkData(EntityPlayer player, Side side, byte id,
			NBTTagCompound nbt, @Nonnull ItemStack stack) {
		if(id == 0) {
			int machineId = nbt.getInteger(IDNAME);
			setMachineId(stack, nbt.getInteger(IDNAME));
			TileMultiBlock tile = machineList.get(machineId);
			setYLevel(stack, tile.getStructure().length-1);
		}
		else if(id == 1) {
			setYLevel(stack, nbt.getInteger("yLevel"));
			Vector3F<Integer> vec = getBasePosition(stack);
			RebuildStructure(player.world, this.machineList.get(getMachineId(stack)), stack, vec.x, vec.y, vec.z, EnumFacing.getFront(getDirection(stack)));
		}
		else if(id == 2) {
			int x = nbt.getInteger("x");
			int y = nbt.getInteger("y");
			int z = nbt.getInteger("z");
			int dir = nbt.getInteger("dir");

			if(getMachineId(stack) != -1)
				RebuildStructure(player.world, this.machineList.get(getMachineId(stack)), stack, x, y, z, EnumFacing.getFront(dir));
		}
	}
}