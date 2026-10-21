package dev.stannismod.stellurgy.client.render.entity;

import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.InstrumentSubject;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.entity.EntitySatellite;
import dev.stannismod.stellurgy.item.ItemSatellite;
import dev.stannismod.stellurgy.libvulpes.util.EmbeddedInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.lwjgl.opengl.GL11;

import java.util.Optional;

/**
 * Draws a satellite from the parts it was built from: the chassis, the dish and two antennas every
 * chassis carries, each power-generation part facing the nearest star, the primary function facing the
 * body or away from it by what it surveys, and the remaining parts on the chassis. Directions are the
 * ones the server computed; until it has, the parts are drawn unturned.
 */
public class RenderSatellite extends Render<EntitySatellite> implements IRenderFactory<EntitySatellite> {

    private static final double DISH_RADIUS = 0.35d;
    private static final int DISH_SEGMENTS = 16;

    public RenderSatellite(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public Render<? super EntitySatellite> createRenderFor(RenderManager manager) {
        return new RenderSatellite(manager);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntitySatellite entity) {
        return TextureMap.LOCATION_BLOCKS_TEXTURE;
    }

    @Override
    public void doRender(EntitySatellite entity, double x, double y, double z, float entityYaw, float partialTicks) {
        Optional<SatelliteBase> satellite = entity.satellite();
        if (!satellite.isPresent()) {
            return;
        }
        ItemStack chassis = satellite.get().getItemStackFromSatellite();
        if (!(chassis.getItem() instanceof ItemSatellite)) {
            return;
        }
        EmbeddedInventory parts = ((ItemSatellite) chassis.getItem()).readInvFromNBT(chassis);
        Vector3dc toStar = entity.toStar();
        Vector3dc toBody = entity.toBody();

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y + entity.height / 2d, z);
        bindEntityTexture(entity);

        drawItem(chassis, new Vector3d(), toBody, 2f);

        Vector3dc instrumentFacing = instrumentFacing(satellite.get(), toBody);
        drawItem(parts.getStackInSlot(0), new Vector3d(instrumentFacing).mul(0.6d), instrumentFacing, 1f);

        Vector3dc across = perpendicular(toStar);
        int panel = 0;
        int other = 0;
        for (int slot = 1; slot < parts.getSizeInventory(); slot++) {
            ItemStack part = parts.getStackInSlot(slot);
            SatelliteProperties props = SatelliteRegistry.getSatelliteProperty(part);
            if (part.isEmpty() || props == null) {
                continue;
            }
            if (props.getPropertyFlag() == SatelliteProperties.Property.POWER_GEN.getFlag()) {
                // Panels spread on alternate sides of the chassis, all facing the star.
                double side = (panel % 2 == 0 ? 1d : -1d) * (0.8d + 0.7d * (panel / 2));
                drawItem(part, new Vector3d(across).mul(side), toStar, 1.2f);
                panel++;
            } else {
                double turn = Math.PI / 2d * other;
                drawItem(part, new Vector3d(Math.cos(turn) * 0.45d, -0.45d, Math.sin(turn) * 0.45d), toBody, 0.6f);
                other++;
            }
        }

        drawDishAndAntennas(toBody);
        GlStateManager.popMatrix();
    }

    /** Toward the body for a survey of the body, away from it for a survey of space; unturned for neither. */
    private static Vector3dc instrumentFacing(SatelliteBase satellite, Vector3dc toBody) {
        Optional<InstrumentSubject> subject = satellite.instrumentSubject();
        if (!subject.isPresent() || toBody.lengthSquared() == 0d) {
            return new Vector3d(0d, 0d, 1d);
        }
        return subject.get() == InstrumentSubject.BODY ? toBody : new Vector3d(toBody).negate();
    }

    private void drawItem(ItemStack stack, Vector3dc at, Vector3dc facing, float scale) {
        if (stack.isEmpty()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(at.x(), at.y(), at.z());
        face(facing);
        GlStateManager.scale(scale, scale, scale);
        Minecraft.getMinecraft().getRenderItem().renderItem(stack, ItemCameraTransforms.TransformType.FIXED);
        GlStateManager.popMatrix();
    }

    /** The dish on the body side of the chassis, open toward the body, and two antennas across it. */
    private static void drawDishAndAntennas(Vector3dc toBody) {
        GlStateManager.pushMatrix();
        face(toBody);
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        buffer.pos(0d, 0d, 0.75d).color(0.85f, 0.85f, 0.9f, 1f).endVertex();
        for (int i = 0; i <= DISH_SEGMENTS; i++) {
            double a = 2d * Math.PI * i / DISH_SEGMENTS;
            buffer.pos(Math.cos(a) * DISH_RADIUS, Math.sin(a) * DISH_RADIUS, 0.55d).color(0.7f, 0.7f, 0.75f, 1f).endVertex();
        }
        tessellator.draw();
        GlStateManager.glLineWidth(2f);
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        buffer.pos(0d, 0.5d, 0d).color(0.6f, 0.6f, 0.6f, 1f).endVertex();
        buffer.pos(0.3d, 1.2d, -0.2d).color(0.6f, 0.6f, 0.6f, 1f).endVertex();
        buffer.pos(0d, 0.5d, 0d).color(0.6f, 0.6f, 0.6f, 1f).endVertex();
        buffer.pos(-0.3d, 1.2d, -0.2d).color(0.6f, 0.6f, 0.6f, 1f).endVertex();
        tessellator.draw();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /** Turn the current frame so its +Z points along {@code direction}; a zero direction leaves it unturned. */
    private static void face(Vector3dc direction) {
        if (direction.lengthSquared() == 0d) {
            return;
        }
        Vector3d d = new Vector3d(direction).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1d, Math.min(1d, d.y))));
        GlStateManager.rotate(yaw, 0f, 1f, 0f);
        GlStateManager.rotate(-pitch, 1f, 0f, 0f);
    }

    /** A unit vector square to {@code v}: the axis the panels spread along. */
    private static Vector3dc perpendicular(Vector3dc v) {
        Vector3d across = new Vector3d(v).cross(0d, 1d, 0d);
        if (across.lengthSquared() < 1e-6d) {
            across = new Vector3d(v).cross(1d, 0d, 0d);
        }
        return across.lengthSquared() < 1e-6d ? new Vector3d(1d, 0d, 0d) : across.normalize();
    }
}
