package dev.stannismod.stellurgy.probe.client;

import dev.stannismod.stellurgy.client.ClientWorldDrawings;
import dev.stannismod.stellurgy.probe.FallPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Optional;

/**
 * Draws the first probe's fall in the sky: a glowing head along its true direction from the eye, and the
 * trail it left over the last seconds, each point placed where {@link ClientProbeFall#skyPoint} puts it.
 * Fast it glows white-hot; slowed by the air it fades to an ember, still drawn, down to the ground.
 */
@SideOnly(Side.CLIENT)
public class RenderProbeFall {

    /**
     * How far from the eye the fall is drawn at most, as a share of the loaded distance: inside the far plane
     * and short of where the fog closes, so nothing between clips or greys it out.
     */
    private static final double DRAW_SHARE = 0.75d;
    /** How long a trail it draws, ticks: the last ten seconds of its path. */
    private static final int TRAIL_TICKS = 200;
    /** Trail points drawn. */
    private static final int TRAIL_POINTS = 40;
    /**
     * The glowing head's radius at its true point when fully hot, metres: a re-entry's plasma sheath and
     * wake read from the ground as tens of metres across.
     */
    private static final double HOT_RADIUS_M = 40d;
    /**
     * The smallest angle the head covers, radians: a quarter of a degree, a bright point and no smaller.
     * Effectively final, process lifetime: built once at class initialisation.
     */
    private static final double MIN_ANGLE_RAD = Math.toRadians(0.25d);
    /** The speeds between which it goes from an ember to white-hot, m/s. */
    private static final double EMBER_SPEED = 1_000d;
    private static final double HOT_SPEED = 4_000d;
    /** How bright the coolest part of the fall is still drawn. */
    private static final double EMBER_FLOOR = 0.25d;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        ClientProbeFall fall = ClientWorldDrawings.of(mc.world).probeFall();
        Optional<FallPlacement> placement = fall.placement();
        Entity view = mc.getRenderViewEntity();
        if (!placement.isPresent() || view == null) {
            return;
        }
        float partial = event.getPartialTicks();
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;
        Vec3d eye = new Vec3d(camX, camY + view.getEyeHeight(), camZ);
        Vec3d eyeFromCamera = new Vec3d(0d, view.getEyeHeight(), 0d);
        double now = mc.world.getTotalWorldTime() + partial;
        double farthest = mc.gameSettings.renderDistanceChunks * 16d * DRAW_SHARE;
        Optional<ClientProbeFall.SkyPoint> head = fall.skyPoint(eye, now, farthest);
        if (!head.isPresent()) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(false);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        // The trail: the path it has flown, each point placed in the sky as the head is.
        GlStateManager.glLineWidth(3f);
        buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        double headTime = head.get().time;
        for (int i = 0; i <= TRAIL_POINTS; i++) {
            double back = TRAIL_TICKS * (double) i / TRAIL_POINTS;
            Optional<ClientProbeFall.SkyPoint> point = fall.skyPoint(eye, now - back, farthest);
            if (!point.isPresent()) {
                break;
            }
            Vec3d p = point.get().drawnOffset().add(eyeFromCamera);
            float heat = (float) heat(placement.get(), headTime - back);
            float fade = 1f - (float) i / TRAIL_POINTS;
            buffer.pos(p.x, p.y, p.z).color(1f, 0.55f * heat + 0.2f, 0.15f * heat, fade * heat).endVertex();
        }
        tessellator.draw();

        // The head: a square facing the eye, covering the angle the glow truly covers, never less than a point.
        ClientProbeFall.SkyPoint at = head.get();
        double heat = heat(placement.get(), at.time);
        double radius = Math.max(HOT_RADIUS_M * heat * at.scale(), MIN_ANGLE_RAD * at.drawDistance);
        Vec3d dir = at.direction;
        Vec3d right = dir.crossProduct(new Vec3d(0d, 1d, 0d));
        right = right.lengthSquared() < 1e-6d ? new Vec3d(1d, 0d, 0d) : right.normalize();
        Vec3d up = right.crossProduct(dir).normalize();
        Vec3d c = at.drawnOffset().add(eyeFromCamera);
        Vec3d r = right.scale(radius);
        Vec3d u = up.scale(radius);
        float h = (float) heat;
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertex(buffer, c.subtract(r).subtract(u), h);
        vertex(buffer, c.add(r).subtract(u), h);
        vertex(buffer, c.add(r).add(u), h);
        vertex(buffer, c.subtract(r).add(u), h);
        tessellator.draw();

        GlStateManager.glLineWidth(1f);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableFog();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static void vertex(BufferBuilder buffer, Vec3d p, float heat) {
        buffer.pos(p.x, p.y, p.z).color(1f, 0.6f + 0.4f * heat, 0.3f + 0.6f * heat, 1f).endVertex();
    }

    /** How hot it glows {@code time} ticks into the fall, from {@link #EMBER_FLOOR} to one, by its speed. */
    private static double heat(FallPlacement placement, double time) {
        int tick = (int) Math.max(0d, Math.min(placement.ticks(), Math.floor(time)));
        double speed = placement.path().speedAt(tick);
        double hot = (speed - EMBER_SPEED) / (HOT_SPEED - EMBER_SPEED);
        return Math.max(EMBER_FLOOR, Math.min(1d, hot));
    }
}
