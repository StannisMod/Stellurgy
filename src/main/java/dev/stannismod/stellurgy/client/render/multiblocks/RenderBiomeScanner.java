package dev.stannismod.stellurgy.client.render.multiblocks;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import dev.stannismod.stellurgy.backwardCompat.ModelFormatException;
import dev.stannismod.stellurgy.backwardCompat.WavefrontObject;
import dev.stannismod.stellurgy.libvulpes.block.RotatableBlock;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiPowerConsumer;

public class RenderBiomeScanner extends TileEntitySpecialRenderer {

    WavefrontObject model;

    ResourceLocation texture = new ResourceLocation("stellurgy:textures/models/biomescanner.png");

    public RenderBiomeScanner() {
        try {
            model = new WavefrontObject(new ResourceLocation("stellurgy:models/biomescanner.obj"));
        } catch (ModelFormatException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void render(TileEntity tile, double x, double y, double z,
                       float partialTicks, int destroyStage, float a) {
        TileMultiPowerConsumer multiBlockTile = (TileMultiPowerConsumer) tile;

        if (!multiBlockTile.canRender())
            return;

        GL11.glPushMatrix();

        //Initial setup

        //Rotate and move the model into position
        EnumFacing front = RotatableBlock.getFront(tile.getWorld().getBlockState(tile.getPos())); //tile.getWorldObj().getBlockMetadata(tile.xCoord, tile.yCoord, tile.zCoord));
        GL11.glTranslated(x, y, z + 1);

        bindTexture(texture);

        model.renderAll();

        GL11.glPopMatrix();
    }

}
