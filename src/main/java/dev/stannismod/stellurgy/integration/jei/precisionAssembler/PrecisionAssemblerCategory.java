package dev.stannismod.stellurgy.integration.jei.precisionAssembler;

import mezz.jei.api.IGuiHelper;
import net.minecraft.util.EnumFacing;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.client.util.ProgressBarImage;

public class PrecisionAssemblerCategory extends MachineCategoryTemplate<PrecisionAssemblerWrapper> {

    public PrecisionAssemblerCategory(IGuiHelper helper) {
        super(helper, new ProgressBarImage(168, 41, 11, 15, 67, 42, 11, 15, EnumFacing.DOWN, TextureResources.progressBars));
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.precisionAssemblerUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.precisionAssemblingMachine.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
