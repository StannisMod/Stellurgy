package dev.stannismod.stellurgy.client.render.planet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * The one shape every sky draws a body with: a sphere seen at its true angle — and, for what is flat by
 * nature (a ring, an accretion disk), a disc. Never a square.
 *
 * <p>A sphere of radius {@code centre * sin(angle)} centred {@code centre} away subtends exactly
 * {@code angle}; {@link #centreDistance} moves the centre out as the angle nears 90 degrees so the near
 * side never comes closer than one unit to the camera. Only the camera-facing hemisphere is emitted, with
 * the body's picture projected onto it from the front, so the face a viewer sees is the picture the sky
 * always drew, now bounded by a sphere's silhouette; the facets past the silhouette turn away and are
 * culled.</p>
 */
@SideOnly(Side.CLIENT)
public final class SkySphere {

    /** Rings from the camera-facing pole to the equator. */
    private static final int RINGS = 12;
    /** Segments around one ring: enough that a world filling half the sky does not read as a polygon. */
    private static final int SEGMENTS = 48;

    private SkySphere() {
    }

    /**
     * The smallest angular radius a body is drawn at on THIS screen, read this frame: the vertical field
     * of view the player set, over the height of the frame, for {@link ApparentSize#MARKER_DIAMETER_PIXELS}.
     */
    public static double markerHalfAngle() {
        Minecraft mc = Minecraft.getMinecraft();
        return ApparentSize.markerHalfAngle(mc.gameSettings.fovSetting, mc.displayHeight);
    }

    /** How big one screen pixel is, in sky units, on a plane {@code distance} from the camera. */
    public static double unitsPerPixelAt(double distance) {
        return distance * Math.tan(markerHalfAngle() * 2.0D / ApparentSize.MARKER_DIAMETER_PIXELS);
    }

    /** Rays round one star: alternately long and short. */
    private static final int RAYS = 12;
    /** How wide a ray is at its base, either side of its axis, in degrees. */
    private static final double RAY_HALF_WIDTH_DEGREES = 3.0D;
    /** The shortest a long ray reaches past the disc, in screen pixels: a star that is a marker still has rays. */
    private static final double MIN_RAY_PIXELS = 6.0D;
    /** How far a long ray reaches past the disc, as a fraction of the disc's radius. */
    private static final double RAY_REACH = 0.45D;

    /**
     * A star's rays, the mark that tells it from a planet: short spikes in its own colour, bright at the
     * disc and fading to nothing at the tip, alternately long and short, laid in the plane through the
     * centre of a sphere seen from -Z whose silhouette is {@code silhouette} across that plane.
     * {@code unitsPerPixel} is how big one screen pixel is at that plane, so a star drawn as a marker still
     * carries rays a viewer can see. Light adds, so the caller has additive blending on.
     */
    public static void emitRays(BufferBuilder buffer, double silhouette, double unitsPerPixel,
                                float r, float g, float b) {
        double reach = Math.max(silhouette * RAY_REACH, MIN_RAY_PIXELS * unitsPerPixel);
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < RAYS; i++) {
            double axis = 2.0D * Math.PI * i / RAYS;
            double tip = silhouette + (i % 2 == 0 ? reach : reach * 0.5D);
            double left = axis - Math.toRadians(RAY_HALF_WIDTH_DEGREES);
            double right = axis + Math.toRadians(RAY_HALF_WIDTH_DEGREES);
            buffer.pos(silhouette * Math.cos(left), silhouette * Math.sin(left), 0.0D).color(r, g, b, 0.8F).endVertex();
            buffer.pos(silhouette * Math.cos(right), silhouette * Math.sin(right), 0.0D).color(r, g, b, 0.8F).endVertex();
            buffer.pos(tip * Math.cos(axis), tip * Math.sin(axis), 0.0D).color(r, g, b, 0.0F).endVertex();
        }
        Tessellator.getInstance().draw();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
    }

    /**
     * How far from the camera to centre a sphere seen at {@code halfAngle}: {@code preferred}, or further
     * when the angle is so wide that the near side would otherwise come closer than one unit.
     */
    public static double centreDistance(double halfAngle, double preferred) {
        return Math.max(preferred, 1.0D / (1.0D - Math.sin(halfAngle)));
    }

    /**
     * The hemisphere of a sphere of {@code radius}, centred at the origin, that faces the camera along -Z,
     * wound so it is front-facing to a camera on -Z. The picture is projected along Z: {@code u} runs with
     * X, and {@code v = 0.5 + vPerY * 0.5 * y / radius}, so a caller whose frame turns Y the other way
     * keeps the picture upright by passing -1.
     */
    public static void emitFacingHemisphere(BufferBuilder buffer, double radius, double vPerY) {
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        for (int ring = 0; ring < RINGS; ring++) {
            double inner = (Math.PI / 2.0D) * ring / RINGS;
            double outer = (Math.PI / 2.0D) * (ring + 1) / RINGS;
            for (int seg = 0; seg < SEGMENTS; seg++) {
                double from = 2.0D * Math.PI * seg / SEGMENTS;
                double to = 2.0D * Math.PI * (seg + 1) / SEGMENTS;
                vertex(buffer, radius, inner, from, vPerY);
                vertex(buffer, radius, inner, to, vPerY);
                vertex(buffer, radius, outer, to, vPerY);
                vertex(buffer, radius, outer, from, vPerY);
            }
        }
        Tessellator.getInstance().draw();
    }

    private static void vertex(BufferBuilder buffer, double radius, double polar, double around, double vPerY) {
        double x = Math.sin(polar) * Math.cos(around);
        double y = Math.sin(polar) * Math.sin(around);
        buffer.pos(radius * x, radius * y, -radius * Math.cos(polar))
                .tex(0.5D + 0.5D * x, 0.5D + vPerY * 0.5D * y).endVertex();
    }

    /**
     * A flat disc of {@code radius} in the XZ plane, centred at the origin, its picture mapped across it as
     * {@code u = 0.5 + 0.5 x / radius}, {@code v = 0.5 + 0.5 z / radius} — what a ring or an accretion disk
     * is drawn on. Both faces are wanted, so the caller turns culling off.
     */
    public static void emitDisc(BufferBuilder buffer, double radius) {
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_TEX);
        for (int seg = 0; seg < SEGMENTS; seg++) {
            double from = 2.0D * Math.PI * seg / SEGMENTS;
            double to = 2.0D * Math.PI * (seg + 1) / SEGMENTS;
            buffer.pos(0.0D, 0.0D, 0.0D).tex(0.5D, 0.5D).endVertex();
            buffer.pos(radius * Math.cos(to), 0.0D, radius * Math.sin(to))
                    .tex(0.5D + 0.5D * Math.cos(to), 0.5D + 0.5D * Math.sin(to)).endVertex();
            buffer.pos(radius * Math.cos(from), 0.0D, radius * Math.sin(from))
                    .tex(0.5D + 0.5D * Math.cos(from), 0.5D + 0.5D * Math.sin(from)).endVertex();
        }
        Tessellator.getInstance().draw();
    }
}
