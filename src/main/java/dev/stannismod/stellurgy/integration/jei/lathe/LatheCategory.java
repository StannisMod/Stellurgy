package dev.stannismod.stellurgy.integration.jei.lathe;

import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import mezz.jei.api.IGuiHelper;

public class LatheCategory extends MachineCategoryTemplate<LatheWrapper> {

    public LatheCategory(IGuiHelper helper) {
        super(helper, TextureResources.latheProgressBar);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.latheUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.lathe.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
