package dev.stannismod.stellurgy.client.render.armor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import dev.stannismod.stellurgy.backwardCompat.WavefrontObject;

public class RenderJetPack extends ModelBiped {
    /**
     * Effectively final, client lifetime: written once by {@link #loadModel()}, which only the client
     * proxy calls, at pre-init. Not an instance field because the armour item builds a new
     * {@code RenderJetPack} for every frame it is drawn, and no object of ours outlives that call.
     */
    private static WavefrontObject model;
    /** Effectively final, process lifetime: built once at class initialisation. */
    private static final ResourceLocation texture = new ResourceLocation("stellurgy:textures/models/jetpack.png");

    ModelBiped biped;

    /** Load the jetpack model. Called once, by the client proxy at pre-init; a second call throws. */
    public static void loadModel() {
        if (model != null) {
            throw new IllegalStateException("the jetpack model is loaded once per client");
        }
        model = WavefrontObject.required(new ResourceLocation("stellurgy:models/jetPack.obj"));
    }

    public RenderJetPack(ModelBiped _default) {
        biped = _default;
    }


    /**
     * Sets the models various rotation angles then renders the model.
     */
    public void render(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scale) {

        //super.render(p_78088_1_, p_78088_2_, p_78088_3_, p_78088_4_, p_78088_5_, p_78088_6_, p_78088_7_);


        biped.setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale, entity);
        biped.bipedRightArm.showModel = true;
        biped.bipedBody.showModel = true;
        biped.bipedLeftArm.showModel = true;
        GL11.glPushMatrix();
        if (entity.isSneaking()) {
            GL11.glTranslatef(0, .25f, 0);
        }

        biped.bipedBody.render(scale);
        biped.bipedLeftArm.render(scale);
        biped.bipedRightArm.render(scale);
        GL11.glPopMatrix();

        GL11.glPushMatrix();
        //GL11.glTranslatef(x, y, z);
        if (entity.isSneaking()) {
            GL11.glRotatef(0.5F * (180F / (float) Math.PI), 1.0F, 0.0F, 0.0F);
            GL11.glTranslatef(0, .2f, 0);
        }
        Minecraft.getMinecraft().renderEngine.bindTexture(texture);
        model.renderAll();
        GL11.glPopMatrix();
    }
}
