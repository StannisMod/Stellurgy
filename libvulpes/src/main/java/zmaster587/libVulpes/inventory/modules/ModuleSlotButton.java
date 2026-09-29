package zmaster587.libVulpes.inventory.modules;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import zmaster587.libVulpes.gui.CommonResources;
import zmaster587.libVulpes.inventory.TextureResources;

import javax.annotation.Nonnull;

public class ModuleSlotButton extends ModuleButton {

	private final ItemStack stack;
	private final World worldObj;

	private static final float BLOCK_ANCHOR_X = 8.0F;
	private static final float BLOCK_ANCHOR_Y = 12.0F;
	private static final float ITEM_ANCHOR_X  = 8.0F;
	private static final float ITEM_ANCHOR_Y  = 8.0F;

	public ModuleSlotButton(int offsetX, int offsetY, int buttonId, IButtonInventory tile, @Nonnull ItemStack slotDisplay, World world) {
		super(offsetX, offsetY, buttonId, "", tile, TextureResources.buttonNull, slotDisplay.getDisplayName(), 16, 16);
		this.stack = slotDisplay;
		this.worldObj = world;
	}

	public ModuleSlotButton(int offsetX, int offsetY, int buttonId, IButtonInventory tile, @Nonnull ItemStack slotDisplay, String extraDisplay, World world) {
		super(offsetX, offsetY, buttonId, "", tile, TextureResources.buttonNull, slotDisplay.getDisplayName() + " \n" + extraDisplay, 16, 16);
		this.stack = slotDisplay;
		this.worldObj = world;
	}

	@SideOnly(Side.CLIENT)
	private boolean shouldRenderAsBlock(@Nonnull ItemStack stack) {
		if (stack.isEmpty()) return false;

		Block block = Block.getBlockFromItem(stack.getItem());
		if (block == Blocks.AIR) return false;

		try {
			IBlockState state = block.getStateFromMeta(stack.getItemDamage());
			if (state.getRenderType() != EnumBlockRenderType.MODEL) return false;

			IBakedModel model = Minecraft.getMinecraft()
					.getBlockRendererDispatcher()
					.getModelForState(state);

			return model != null && !model.isBuiltInRenderer();
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
		TextureManager textureManager = Minecraft.getMinecraft().getTextureManager();

		textureManager.bindTexture(CommonResources.genericBackground);
		gui.drawTexturedModalRect(x + offsetX - 1, y + offsetY - 1, 176, 0, 18, 18);

		if (stack.isEmpty()) {
			return;
		}

		int drawX = x + offsetX;
		int drawY = y + offsetY;

		boolean rendered = false;

		if (shouldRenderAsBlock(stack)) {
			try {
				renderSpinningBlock(drawX, drawY);
				rendered = true;
			} catch (Exception ignored) {
			}
		}

		if (!rendered) {
			try {
				renderSpinningItem(drawX, drawY);
			} catch (Exception ignored) {
			}
		}

		GlStateManager.color(1f, 1f, 1f, 1f);
		GlStateManager.enableBlend();
		GlStateManager.disableLighting();
		GlStateManager.disableRescaleNormal();
		GlStateManager.depthMask(true);
	}

	@SideOnly(Side.CLIENT)
	private void renderSpinningBlock(int drawX, int drawY) {
		Minecraft mc = Minecraft.getMinecraft();
		TextureManager textureManager = mc.getTextureManager();
		int zLevel = 500;

		textureManager.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

		GlStateManager.enableDepth();
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glAlphaFunc(GL11.GL_GREATER, 0.5F);
		GL11.glDisable(GL11.GL_BLEND);

		GL11.glPushMatrix();
		GL11.glTranslatef(drawX + BLOCK_ANCHOR_X, drawY + BLOCK_ANCHOR_Y, -3.0F + zLevel);
		GL11.glScalef(10.0F, 10.0F, 10.0F);

		GL11.glScalef(1.0F, 1.0F, -1.0F);
		GL11.glRotatef(210.0F, 1.0F, 0.0F, 0.0F);
		GL11.glRotated(45.0F + ((System.currentTimeMillis() % 200000L) / 50F) * 2, 0.0F, 1.0F, 0.0F);
		GL11.glTranslatef(-0.5f, -255f, -0.5f);

		GlStateManager.depthMask(true);

		BufferBuilder buffer = Tessellator.getInstance().getBuffer();
		Block itemBlock = Block.getBlockFromItem(stack.getItem());

		if (itemBlock != Blocks.AIR) {
			IBlockState blockState = itemBlock.getStateFromMeta(stack.getItemDamage());
			buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
			mc.getBlockRendererDispatcher().renderBlock(blockState, new BlockPos(0, 255, 0), worldObj, buffer);
			Tessellator.getInstance().draw();
		}

		GL11.glPopMatrix();
	}

	@SideOnly(Side.CLIENT)
	private void renderSpinningItem(int drawX, int drawY) {
		Minecraft mc = Minecraft.getMinecraft();
		RenderItem renderItem = mc.getRenderItem();
		TextureManager textureManager = mc.getTextureManager();
		int zLevel = 500;

		IBakedModel model = renderItem.getItemModelWithOverrides(stack, worldObj, null);
		if (model == null) {
			return;
		}

		textureManager.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

		GlStateManager.pushMatrix();
		GlStateManager.color(1f, 1f, 1f, 1f);
		GlStateManager.enableDepth();
		GlStateManager.depthMask(true);
		GlStateManager.enableRescaleNormal();
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
		GL11.glDisable(GL11.GL_BLEND);

		RenderHelper.enableStandardItemLighting();

		GlStateManager.translate(drawX + ITEM_ANCHOR_X, drawY + ITEM_ANCHOR_Y, -3.0F + zLevel);
		GlStateManager.scale(10.0F, 10.0F, 10.0F);

		GlStateManager.scale(1.0F, 1.0F, -1.0F);
		GlStateManager.rotate(210.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate((float) (45.0F + ((System.currentTimeMillis() % 200000L) / 50F) * 2), 0.0F, 1.0F, 0.0F);

		renderItem.renderItem(stack, model);

		RenderHelper.disableStandardItemLighting();
		GlStateManager.disableRescaleNormal();
		GlStateManager.popMatrix();
	}
}