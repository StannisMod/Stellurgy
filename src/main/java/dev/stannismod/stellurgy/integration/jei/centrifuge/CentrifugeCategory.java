package dev.stannismod.stellurgy.integration.jei.centrifuge;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import zmaster587.libVulpes.LibVulpes;

public class CentrifugeCategory extends MachineCategoryTemplate<CentrifugeWrapper> {

    public CentrifugeCategory(IGuiHelper helper) {
        super(helper, TextureResources.crystallizerProgressBar);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.centrifugeUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.centrifuge.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
