package dev.stannismod.stellurgy.integration.jei.precisionLaserEtcher;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class PrecisionLaserEtcherCategory extends MachineCategoryTemplate<PrecisionLaserEtcherWrapper> {

    public PrecisionLaserEtcherCategory(IGuiHelper helper) {
        super(helper, TextureResources.latheProgressBar);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.precisionLaserEngraverUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.precisionlaseretcher.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
