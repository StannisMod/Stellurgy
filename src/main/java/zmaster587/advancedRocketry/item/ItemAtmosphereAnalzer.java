package zmaster587.advancedRocketry.item;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.api.atmosphere.Atmosphere;
import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.event.RocketEventHandler;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.api.IArmorComponent;
import zmaster587.libVulpes.client.ResourceIcon;
import zmaster587.libVulpes.render.RenderHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;

public class ItemAtmosphereAnalzer extends Item implements IArmorComponent {

    private static ResourceIcon icon;
    private static ResourceLocation eyeCandySpinner = new ResourceLocation("advancedrocketry:textures/gui/eyeCandy/spinnyThing.png");

    private static String breathable = LibVulpes.proxy.getLocalizedString("msg.atmanal.canbreathe");
    private static String atmtype = LibVulpes.proxy.getLocalizedString("msg.atmanal.atmtype");
    private static String yes = LibVulpes.proxy.getLocalizedString("msg.yes");
    private static String no = LibVulpes.proxy.getLocalizedString("msg.no");

    @Override
    public void onTick(World world, EntityPlayer player, @Nonnull ItemStack armorStack,
                       IInventory modules, @Nonnull ItemStack componentStack) {

    }

    /**
      * The client's own readout, built entirely from what the server last said. Nothing here consults
      * a model: the analyser is a display, and a display that reasoned would be a second answer to
      * questions the server has already answered.
      */
    private List<ITextComponent> getClientReadout() {
        zmaster587.advancedRocketry.atmosphere.AtmosphereSummary summary =
                AtmosphereHandler.currentSummary;
        List<ITextComponent> str = new LinkedList<>();

        // The label is the statements that are true of the air, in order — which is what a name IS
        // once nothing branches on it. Air that asserts nothing in particular says so.
        StringBuilder label = new StringBuilder();
        for (String assertion : summary.assertions()) {
            if (label.length() > 0) {
                label.append(", ");
            }
            label.append(zmaster587.libVulpes.LibVulpes.proxy.getLocalizedString(
                    "msg.atmosphere.assertion." + assertion.toLowerCase(java.util.Locale.ROOT)));
        }

        str.add(new TextComponentTranslation("%s %s %s",
                new TextComponentTranslation("msg.atmanal.atmtype"),
                new TextComponentString(label.toString()),
                new TextComponentString(summary.pressureCentiAtm() / 100f + " atm")));
        str.add(new TextComponentTranslation("%s %s",
                new TextComponentTranslation("msg.atmanal.canbreathe"),
                summary.breathable() ? new TextComponentTranslation("msg.yes")
                        : new TextComponentTranslation("msg.no")));
        return str;
    }

    /**
      * The SERVER's readout, asked of the handler where the player is standing.
      * <p>
      * It used to read the client mirror of the pressure — a static that a dedicated server never
      * fills — so the number a player got by right-clicking was the dimension's nominal density
      * rather than the air he was standing in. Being handed the pressure makes the caller say where
      * the reading came from.
      */
    private List<ITextComponent> getAtmosphereReadout(@Nonnull ItemStack stack, @Nullable Atmosphere atm,
                                                      int pressureCentiAtm, @Nonnull World world) {
        if (atm == null)
            atm = Atmosphere.AIR;


        List<ITextComponent> str = new LinkedList<>();

        str.add(new TextComponentTranslation("%s %s %s",
                new TextComponentTranslation("msg.atmanal.atmtype"),
                new TextComponentTranslation(atm.getUnlocalizedName()),
                new TextComponentString((pressureCentiAtm == -1 ? (DimensionManager.getInstance().isDimensionCreated(world.provider.getDimension()) ? DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension()).getAtmosphereDensity() / 100f : 1) : pressureCentiAtm / 100f) + " atm")
        ));
        str.add(new TextComponentTranslation("%s %s",
                new TextComponentTranslation("msg.atmanal.canbreathe"),
                atm.isBreathable() ? new TextComponentTranslation("msg.yes") : new TextComponentTranslation("msg.no")));

        return str;
    }

    @Override
    @Nonnull
    public ActionResult<ItemStack> onItemRightClick(@Nonnull World worldIn, @Nonnull EntityPlayer playerIn, @Nonnull EnumHand hand) {
        ItemStack stack = playerIn.getHeldItem(hand);
        if (!worldIn.isRemote) {
            AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(worldIn.provider.getDimension());
            List<ITextComponent> str = getAtmosphereReadout(stack,
                    atmhandler == null ? null : (Atmosphere) atmhandler.getAtmosphereType(playerIn),
                    atmhandler == null ? -1 : atmhandler.getAtmospherePressure(playerIn), worldIn);
            for (ITextComponent str1 : str)
                playerIn.sendMessage(str1);
        }
        return super.onItemRightClick(worldIn, playerIn, hand);
    }

    @Override
    public boolean onComponentAdded(World world, @Nonnull ItemStack armorStack) {
        return true;
    }

    @Override
    public void onComponentRemoved(World world, @Nonnull ItemStack armorStack) {
    }

    @Override
    public void onArmorDamaged(EntityLivingBase entity, @Nonnull ItemStack armorStack,
                               @Nonnull ItemStack componentStack, DamageSource source, int damage) {

    }

    @Override
    public boolean isAllowedInSlot(@Nonnull ItemStack componentStack, EntityEquipmentSlot targetSlot) {
        return targetSlot == EntityEquipmentSlot.HEAD;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.atmanalyzer", insertAt);
    }


    @Override
    @SideOnly(Side.CLIENT)
    public void renderScreen(@Nonnull ItemStack componentStack, List<ItemStack> modules,
                             RenderGameOverlayEvent event, Gui gui) {

        FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;

        int screenX = RocketEventHandler.atmBar.getRenderX();//8;
        int screenY = RocketEventHandler.atmBar.getRenderY();//event.getResolution().getScaledHeight() - fontRenderer.FONT_HEIGHT*3;

        List<ITextComponent> str = getClientReadout();
        //Draw BG
        gui.drawString(fontRenderer, str.get(0).getFormattedText(), screenX, screenY, 0xaaffff);
        gui.drawString(fontRenderer, str.get(1).getFormattedText(), screenX, screenY + fontRenderer.FONT_HEIGHT * 4 / 3, 0xaaffff);

        //Render Eyecandy
        GL11.glColor3f(1f, 1f, 1f);
        GL11.glPushMatrix();
        Minecraft.getMinecraft().renderEngine.bindTexture(eyeCandySpinner);
        GL11.glTranslatef(screenX + 12, screenY + 8, 0);
        GL11.glRotatef((System.currentTimeMillis() / 100f) % 360, 0, 0, 1);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        RenderHelper.renderNorthFaceWithUV(buffer, -1, -16, -16, 16, 16, 0, 1, 0, 1);
        Tessellator.getInstance().draw();
        GL11.glPopMatrix();


        Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        RenderHelper.renderNorthFaceWithUV(buffer, -1, screenX - 8, screenY - 12, screenX + 8, screenY + 26, 0, 0.25f, 0, 1);
        RenderHelper.renderNorthFaceWithUV(buffer, -1, screenX + 8, screenY - 12, screenX + 212, screenY + 26, 0.5f, 0.5f, 0, 1);
        RenderHelper.renderNorthFaceWithUV(buffer, -1, screenX + 212, screenY - 12, screenX + 228, screenY + 26, 0.75f, 1f, 0, 1);
        Tessellator.getInstance().draw();
    }

    @Override
    public ResourceIcon getComponentIcon(@Nonnull ItemStack armorStack) {
        return null;
    }
}
