package dev.stannismod.stellurgy.integration.jei.platePresser;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class PlatePressCategory extends MachineCategoryTemplate<PlatePressWrapper> {

    public PlatePressCategory(IGuiHelper helper) {
        super(helper, TextureResources.smallPlatePresser);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.platePresser;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.platepress.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
