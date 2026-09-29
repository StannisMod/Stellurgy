package dev.stannismod.stellurgy.integration.jei.sawmill;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class SawMillCategory extends MachineCategoryTemplate<SawMillWrapper> {

    public SawMillCategory(IGuiHelper helper) {
        super(helper, TextureResources.cuttingMachineProgressBar);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.sawMillUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.cuttingMachine.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
