package dev.stannismod.stellurgy.integration.jei.crystallizer;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class CrystallizerCategory extends MachineCategoryTemplate<CrystallizerWrapper> {

    public CrystallizerCategory(IGuiHelper helper) {
        super(helper, TextureResources.crystallizerProgressBar);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.crystallizerUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.Crystallizer.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
