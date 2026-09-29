package dev.stannismod.stellurgy.integration.jei.electrolyser;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import zmaster587.libVulpes.LibVulpes;

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
