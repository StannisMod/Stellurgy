package dev.stannismod.stellurgy.client.render;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3i;
import dev.stannismod.stellurgy.backwardCompat.WavefrontObject;
import dev.stannismod.stellurgy.tile.TileBrokenPart;
import dev.stannismod.stellurgy.util.IBrokenPartBlock;
import dev.stannismod.stellurgy.libvulpes.block.BlockFullyRotatable;

import java.util.HashMap;
import java.util.Map;

public class RendererBrokenPart extends TileEntitySpecialRenderer<TileBrokenPart> {

    private final Map<ResourceLocation, WavefrontObject> models = new HashMap<>();

    private WavefrontObject model(ResourceLocation location) {
        return models.computeIfAbsent(location, WavefrontObject::required);
    }

    @Override
    public void render(TileBrokenPart tile, double x, double y, double z, float t, int destroyStage, float a) {
        ResourceLocation res = tile.getBlockType().getRegistryName();
        Block blk = tile.getBlockType();

        if ((blk instanceof IBrokenPartBlock)) {
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) x, (float) y, (float) z);

            String name = blk.getUnlocalizedName().split("\\.")[1].toLowerCase();
            String pathToTexture = "textures/models/" + name + "_" + tile.getStage() / 3 + ".png";

            if (tile.getBlockType() instanceof BlockFullyRotatable) {
                IBlockState state = tile.getWorld().getBlockState(tile.getPos());
                EnumFacing facing = state.getBlock().getActualState(state, tile.getWorld(), tile.getPos()).getValue(BlockFullyRotatable.FACING);
                Vec3i dir = facing.getDirectionVec();
                GlStateManager.translate(0.5F, 0.5F, 0.5F);
                if (dir.getY() > 0) {
                    GlStateManager.rotate(180.0F, 1.0F, 0.0F, 0.0F);
                }
                if (dir.getX() != 0) {
                    GlStateManager.rotate(90.0F, 0.0F, 0.0F, -dir.getX());
                }
                if (dir.getZ() != 0) {
                    GlStateManager.rotate(90.0F, dir.getZ(), 0.0F, 0.0F);
                }
                GlStateManager.translate(-0.5F, -0.5F, -0.5F);
            }

            if (destroyStage >= 0) {
                this.bindTexture(DESTROY_STAGES[destroyStage]);
                GlStateManager.matrixMode(5890);
                GlStateManager.pushMatrix();
                GlStateManager.scale(4.0F, 4.0F, 1.0F);
                GlStateManager.translate(0.0625F, 0.0625F, 0.0625F);
                GlStateManager.matrixMode(5888);
            } else {
                this.bindTexture(new ResourceLocation(res.getResourceDomain(), pathToTexture));
            }

            model(new ResourceLocation(res.getResourceDomain(), "models/block/models/" + name + ".obj")).renderAll();

            if (destroyStage >= 0) {
                GlStateManager.matrixMode(5890);
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(5888);
            }

            GlStateManager.popMatrix();
        }
    }
}
