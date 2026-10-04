package dev.stannismod.stellurgy.client.render.entity;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import org.lwjgl.opengl.GL11;
import dev.stannismod.stellurgy.backwardCompat.ModelFormatException;
import dev.stannismod.stellurgy.backwardCompat.WavefrontObject;
import dev.stannismod.stellurgy.entity.EntityElevatorCapsule;

public class RenderElevatorCapsule extends Render<EntityElevatorCapsule> implements IRenderFactory<EntityElevatorCapsule> {

    private final WavefrontObject sphere =
            WavefrontObject.required(new ResourceLocation("stellurgy:models/spaceElevator.obj"));

    public ResourceLocation capsuleTexture = new ResourceLocation("stellurgy:textures/models/spaceElevatorCapsule.png");

    public RenderElevatorCapsule(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public Render<? super EntityElevatorCapsule> createRenderFor(
            RenderManager manager) {
        return new RenderElevatorCapsule(manager);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityElevatorCapsule entity) {
        return capsuleTexture;
    }

    @Override
    public boolean shouldRender(EntityElevatorCapsule livingEntity,
                                ICamera camera, double camX, double camY, double camZ) {
        // TODO Auto-generated method stub
        //return super.shouldRender(livingEntity, camera, camX, camY, camZ);
        return true;
    }

    @Override
    public void doRender(EntityElevatorCapsule entity, double x, double y, double z,
                         float entityYaw, float partialTicks) {
        GL11.glPushMatrix();
        GL11.glTranslated(x, y + 1, z);
        GL11.glRotated(entityYaw, 0, 1, 0);
        bindTexture(capsuleTexture);
        sphere.renderOnly("Capsule");

        if (entity.isInMotion())
            sphere.renderOnly("Door");

        GL11.glPopMatrix();
    }
}
