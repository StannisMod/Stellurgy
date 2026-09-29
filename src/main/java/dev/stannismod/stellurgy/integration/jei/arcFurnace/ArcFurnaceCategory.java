package dev.stannismod.stellurgy.integration.jei.arcFurnace;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import zmaster587.libVulpes.LibVulpes;

import javax.annotation.Nonnull;

public class ArcFurnaceCategory extends MachineCategoryTemplate<ArcFurnaceWrapper> {

    public ArcFurnaceCategory(IGuiHelper helper) {
        super(helper, TextureResources.arcFurnaceProgressBar);
    }

    @Override
    @Nonnull
    public String getUid() {
        return StellurgyJeiPlugin.arcFurnaceUUID;
    }

    @Override
    @Nonnull
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.electricArcFurnace.name");
    }

    @Override
    @Nonnull
    public String getModName() {
        return "Stellurgy";
    }

}
