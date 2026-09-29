package dev.stannismod.stellurgy.integration.jei.rollingMachine;

import mezz.jei.api.IGuiHelper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import dev.stannismod.stellurgy.integration.jei.MachineCategoryTemplate;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class RollingMachineCategory extends MachineCategoryTemplate<RollingMachineWrapper> {

    public RollingMachineCategory(IGuiHelper helper) {
        super(helper, TextureResources.rollingMachineProgressBar);
    }

    @Override
    public String getUid() {
        return StellurgyJeiPlugin.rollingMachineUUID;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.rollingMachine.name");
    }

    @Override
    public String getModName() {
        return "Stellurgy";
    }

}
