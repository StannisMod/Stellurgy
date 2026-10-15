package dev.stannismod.stellurgy.integration.jei.electrolyser;

import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import mezz.jei.api.IGuiHelper;

public class ElectrolyzerCategory extends MachineCategoryTemplate<ElectrolyzerWrapper> {

    public ElectrolyzerCategory(IGuiHelper helper) {
        super(helper, TextureResources.crystallizerProgressBar);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.electrolyzerUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.electrolyser.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
