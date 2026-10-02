package org.valkyrienskies.mod.client.render;

import java.lang.reflect.Method;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import dev.stannismod.stellurgy.world.WorldRuntime;

/**
 * Draws the vertex buffers a ship's render chunks are built into, and owns the scratch buffer they
 * are built in.
 */
public class FastBlockModelRenderer {

    /**
     * OptiFine's {@code Config.isShaders}, or {@code null} when OptiFine is not installed. Whether a
     * class is on the classpath is a fact of the process, the same whenever this class loads; whether
     * shaders are ON is not, so it is asked on every draw rather than remembered.
     *
     * Effectively final, process lifetime: built once at class initialisation.
     */
    private static final Method OPTIFINE_IS_SHADERS = optifineIsShaders();

    private static Method optifineIsShaders() {
        try {
            return Class.forName("Config", false, FastBlockModelRenderer.class.getClassLoader())
                .getMethod("isShaders");
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (NoSuchMethodException e) {
            // A default-package "Config" that is not OptiFine's: there are no OptiFine shaders to
            // ask about, and the ship chunks draw with the vanilla vertex layout.
            System.err.println("[VS] a class named Config is present but has no isShaders(); "
                + "drawing ships without OptiFine shader support");
            return null;
        }
    }

    private static boolean areOptifineShadersEnabled() {
        if (OPTIFINE_IS_SHADERS == null) {
            return false;
        }
        try {
            return (Boolean) OPTIFINE_IS_SHADERS.invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** The scratch buffer one client world's ship chunks are built into, as that world's part
     *  ({@link WorldRuntime}). Render thread only. */
    private static final class ChunkBuilder {
        final BufferBuilder buffer = new BufferBuilder(500000);
    }

    /** The scratch buffer to build {@code world}'s ship render chunks in. Render thread only. */
    public static BufferBuilder chunkBuilderFor(World world) {
        return WorldRuntime.of(world, ChunkBuilder.class, ChunkBuilder::new).buffer;
    }

    public static void renderVertexBuffer(VertexBuffer vertexBuffer) {
        final boolean areOptifineShadersEnabled = areOptifineShadersEnabled();

        GlStateManager.pushMatrix();
        GlStateManager.resetColor();

        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);

        // Extra OpenGL states that must be enabled when shaders are enabled.
        if (areOptifineShadersEnabled) {
            GL11.glEnableClientState(32885);
            GL20.glEnableVertexAttribArray(11);
            GL20.glEnableVertexAttribArray(12);
            GL20.glEnableVertexAttribArray(10);
        }

        GlStateManager.pushMatrix();
        vertexBuffer.bindBuffer();

        // Even more OpenGL states that must be enabled when shaders are enabled.
        if (areOptifineShadersEnabled) {
            GL11.glVertexPointer(3, 5126, 56, 0L);
            GL11.glColorPointer(4, 5121, 56, 12L);
            GL11.glTexCoordPointer(2, 5126, 56, 16L);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glTexCoordPointer(2, 5122, 56, 24L);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glNormalPointer(5120, 56, 28L);
            GL20.glVertexAttribPointer(11, 2, 5126, false, 56, 32L);
            GL20.glVertexAttribPointer(12, 4, 5122, false, 56, 40L);
            GL20.glVertexAttribPointer(10, 3, 5122, false, 56, 48L);
        } else {

            GlStateManager.glVertexPointer(3, 5126, 28, 0);
            GlStateManager.glColorPointer(4, 5121, 28, 12);
            GlStateManager.glTexCoordPointer(2, 5126, 28, 16);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.glTexCoordPointer(2, 5122, 28, 24);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        }

        vertexBuffer.drawArrays(7);
        GlStateManager.popMatrix();
        vertexBuffer.unbindBuffer();
        GlStateManager.resetColor();

        for (VertexFormatElement vertexformatelement : DefaultVertexFormats.BLOCK.getElements()) {
            VertexFormatElement.EnumUsage vertexformatelement$enumusage = vertexformatelement
                .getUsage();
            int i = vertexformatelement.getIndex();

            switch (vertexformatelement$enumusage) {
                case POSITION:
                    GlStateManager.glDisableClientState(32884);
                    break;
                case UV:
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit + i);
                    GlStateManager.glDisableClientState(32888);
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                    break;
                case COLOR:
                    GlStateManager.glDisableClientState(32886);
                    GlStateManager.resetColor();
            }
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

        // Finally disable some of those extra OpenGL states that were be enabled due to shaders.
        if (areOptifineShadersEnabled) {
            GL11.glDisableClientState(32885);
            GL20.glDisableVertexAttribArray(11);
            GL20.glDisableVertexAttribArray(12);
            GL20.glDisableVertexAttribArray(10);
        }

        GlStateManager.resetColor();
        GlStateManager.popMatrix();
    }
}
