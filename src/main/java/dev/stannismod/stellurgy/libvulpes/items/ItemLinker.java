package dev.stannismod.stellurgy.libvulpes.items;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import dev.stannismod.stellurgy.libvulpes.interfaces.ILinkAimedTile;
import dev.stannismod.stellurgy.libvulpes.interfaces.ILinkableTile;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

import javax.annotation.Nonnull;
import java.util.List;

public class ItemLinker extends Item {

	protected int linkX,linkY,linkZ, dimId;
	private final static int EMPTYSETTING = 0;

	public ItemLinker() {
		super();

		this.maxStackSize = 1;
		this.setCreativeTab(CreativeTabs.TRANSPORTATION);
		dimId = 0;
	}


	@Override
	public void addInformation(@Nonnull ItemStack par1ItemStack, World par2EntityPlayer, List<String> par3List, ITooltipFlag par4)
	{
		int y = getMasterY(par1ItemStack);

		if(y == 0){
			par3List.add(LibVulpes.proxy.getLocalizedString("msg.libvulpes.linker.tooltip.coordsUnset"));
		}
		else {
			par3List.add(LibVulpes.proxy.getLocalizedString("msg.libvulpes.linker.tooltip.x") + " " + getMasterX(par1ItemStack));
			par3List.add(LibVulpes.proxy.getLocalizedString("msg.libvulpes.linker.tooltip.y") + " " + getMasterY(par1ItemStack));
			par3List.add(LibVulpes.proxy.getLocalizedString("msg.libvulpes.linker.tooltip.z") + " " + getMasterZ(par1ItemStack));
			int dimId = getDimId(par1ItemStack);
			if(dimId != -1)
				par3List.add(LibVulpes.proxy.getLocalizedString("msg.libvulpes.linker.tooltip.dim") + " " + dimId);
		}
	}

	public static boolean isSet(@Nonnull ItemStack stack) {
		return getMasterY(stack) != 0;
	}

	@Deprecated
	public static int getMasterX(@Nonnull ItemStack itemStack) {
		NBTTagCompound nbt = itemStack.getTagCompound();
		nbt = nbt.getCompoundTag("MasterPos");

		return nbt.getInteger("MasterX");
	}
	@Deprecated
	public static int getMasterY(@Nonnull ItemStack itemStack) {
		NBTTagCompound nbt = itemStack.getTagCompound();

		if(nbt == null)
			return 0;

		nbt = nbt.getCompoundTag("MasterPos");

		return nbt.getInteger("MasterY");
	}
	@Deprecated
	public static int getMasterZ(@Nonnull ItemStack itemStack) {
		NBTTagCompound nbt = itemStack.getTagCompound();
		nbt = nbt.getCompoundTag("MasterPos");

		return nbt.getInteger("MasterZ");
	}
	
	public static void setDimId(@Nonnull ItemStack itemStack, int id) {
		NBTTagCompound nbt;
		if(!itemStack.hasTagCompound()) {
			nbt = new NBTTagCompound();
			itemStack.setTagCompound(nbt);
		}
		else
			nbt = itemStack.getTagCompound();
		
		nbt.setInteger("dimId", id);
		
	}
	
	public static int getDimId(@Nonnull ItemStack itemStack) {
		NBTTagCompound nbt;
		if(!itemStack.hasTagCompound()) {
			nbt = new NBTTagCompound();
	    } else
	        nbt = itemStack.getTagCompound();
		
		return nbt.hasKey("dimId") ? nbt.getInteger("dimId") : -1;
		
	}

	public static void setMasterX(@Nonnull ItemStack itemStack, int num) {
		NBTTagCompound nbt = itemStack.getTagCompound();
		nbt = nbt.getCompoundTag("MasterPos");

		nbt.setInteger("MasterX", num);
	}

	public static void setMasterY(@Nonnull ItemStack itemStack, int num) {
		NBTTagCompound nbt = itemStack.getTagCompound();
		nbt = nbt.getCompoundTag("MasterPos");

		nbt.setInteger("MasterY", num);
	}

	public static void setMasterZ(@Nonnull ItemStack itemStack, int num) {
		NBTTagCompound nbt = itemStack.getTagCompound();
		nbt = nbt.getCompoundTag("MasterPos");

		nbt.setInteger("MasterZ", num);
	}

	public static void setMasterCoords(@Nonnull ItemStack stack , int x, int y, int z) {
		NBTTagCompound nbt;
		if(!stack.hasTagCompound()) {
			nbt = new NBTTagCompound();
			stack.setTagCompound(nbt);
		}
		else {
			nbt = stack.getTagCompound();
		}
		NBTTagCompound tag = new NBTTagCompound();
		
		tag.setInteger("MasterX", x);
		tag.setInteger("MasterY", y);
		tag.setInteger("MasterZ", z);
		nbt.setTag("MasterPos", tag);
	}
	
	public static void setMasterCoords(@Nonnull ItemStack stack, BlockPos pos) {
		setMasterCoords(stack, pos.getX(), pos.getY(), pos.getZ());
	}

	public static BlockPos getMasterCoords(@Nonnull ItemStack stack ) {
		if(!stack.hasTagCompound()) {

			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setTag("MasterPos", new NBTTagCompound());
			stack.setTagCompound(nbt);
		}
		
		return new BlockPos(getMasterX(stack),getMasterY(stack),getMasterZ(stack));
	}
	
	public static void resetPosition(@Nonnull ItemStack itemStack) {
		NBTTagCompound position = new NBTTagCompound();

		position.setInteger("MasterX", EMPTYSETTING);
		position.setInteger("MasterY", EMPTYSETTING);
		position.setInteger("MasterZ", EMPTYSETTING);

		itemStack.setTagInfo("MasterPos", position);
		setDimId(itemStack, -1);
	}
	@Override
	@Nonnull
	public EnumActionResult onItemUse(EntityPlayer playerIn, World worldIn,
			BlockPos pos, EnumHand hand, EnumFacing facing, float hitX,
			float hitY, float hitZ) {
		
		TileEntity entity = worldIn.getTileEntity(pos);

		ItemStack stack = playerIn.getHeldItem(hand);
		
		if(entity != null) {
			if(entity instanceof ILinkableTile) {
				applySettings(stack, (ILinkableTile)entity, playerIn, worldIn);
				return EnumActionResult.SUCCESS;
			}
		}
		else if(playerIn.isSneaking()) {
			resetPosition(stack);
			return EnumActionResult.SUCCESS;
		}

		return EnumActionResult.FAIL;
	}

	protected void applySettings(@Nonnull ItemStack itemStack, ILinkableTile pad, EntityPlayer player, World world) {
		if(!isSet(itemStack))
			pad.onLinkStart(itemStack, (TileEntity)pad, player, world);
		else
			pad.onLinkComplete(itemStack, (TileEntity)pad, player, world);
	}

	/**
	 * A right-click that is not on a linkable machine, with a linker bound to an
	 * {@link ILinkAimedTile}: that tile is handed what the player's line of sight lands on.
	 *
	 * <p>Reached for a click into the air and also for a click on an ordinary block within arm's
	 * reach, because {@link #onItemUse} answers FAIL there and vanilla then offers the same click
	 * here. A linker bound to any other kind of machine does nothing, as it always did.</p>
	 *
	 * <p>A binding written without a dimension ({@code dimId -1}, which several older machines
	 * write) is read as this dimension, the only one it can have been made in that this click can
	 * reach.</p>
	 */
	@Override
	@Nonnull
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
		ItemStack stack = player.getHeldItem(hand);
		if (world.isRemote || !isSet(stack))
			return new ActionResult<>(EnumActionResult.PASS, stack);

		int boundDim = getDimId(stack);
		BlockPos bound = getMasterCoords(stack);
		boolean here = boundDim == -1 || boundDim == world.provider.getDimension();
		if (!here || !world.isBlockLoaded(bound)) {
			// We cannot tell what it is bound to, so we cannot tell that this click was not meant
			// for it; saying nothing would read as a designation that was taken.
			player.sendMessage(new TextComponentTranslation("msg.linker.aim.boundNotLoaded"));
			return new ActionResult<>(EnumActionResult.FAIL, stack);
		}
		TileEntity tile = world.getTileEntity(bound);
		if (!(tile instanceof ILinkAimedTile))
			return new ActionResult<>(EnumActionResult.PASS, stack);

		RayTraceResult aimedAt = lineOfSight(player, (WorldServer) world);
		if (aimedAt == null) {
			player.sendMessage(new TextComponentTranslation("msg.linker.aim.nothingInSight"));
			return new ActionResult<>(EnumActionResult.FAIL, stack);
		}
		boolean taken = ((ILinkAimedTile) tile).onLinkAimed(stack, aimedAt, player);
		return new ActionResult<>(taken ? EnumActionResult.SUCCESS : EnumActionResult.FAIL, stack);
	}

	/**
	 * The first entity or block the player's line of sight crosses, or null when it crosses
	 * nothing.
	 *
	 * <p>Out to the server's view distance: that is the farthest the server keeps the world loaded
	 * around a player, so it is the farthest anything he can see exists here at all — and a trace
	 * past it would load and generate chunks for a click. The loaded square reaches at least that far
	 * along every axis from any point of the player's own chunk, so the whole ray stays inside it.</p>
	 */
	private static RayTraceResult lineOfSight(EntityPlayer player, WorldServer world) {
		double range = world.getMinecraftServer().getPlayerList().getViewDistance() * 16.0D;
		Vec3d eye = player.getPositionEyes(1.0F);
		Vec3d end = eye.add(player.getLook(1.0F).scale(range));

		RayTraceResult hit = world.rayTraceBlocks(eye, end, false, true, false);
		if (hit != null && hit.typeOfHit != RayTraceResult.Type.BLOCK)
			hit = null;
		double nearest = hit == null ? range : eye.distanceTo(hit.hitVec);

		// The six-double constructor: the (Vec3d, Vec3d) one is client-only in 1.12 and is not there
		// on a dedicated server.
		AxisAlignedBB swept = new AxisAlignedBB(eye.x, eye.y, eye.z, end.x, end.y, end.z).grow(1.0D);
		List<Entity> along = world.getEntitiesInAABBexcluding(player, swept, EntitySelectors.NOT_SPECTATING);
		for (Entity candidate : along) {
			if (!candidate.canBeCollidedWith())
				continue;
			RayTraceResult crossing = candidate.getEntityBoundingBox()
					.grow(candidate.getCollisionBorderSize()).calculateIntercept(eye, end);
			if (crossing == null)
				continue;
			double distance = eye.distanceTo(crossing.hitVec);
			if (distance < nearest) {
				nearest = distance;
				hit = new RayTraceResult(candidate, crossing.hitVec);
			}
		}
		return hit;
	}
}
