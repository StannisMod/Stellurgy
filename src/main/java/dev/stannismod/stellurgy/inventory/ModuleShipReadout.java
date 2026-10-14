package dev.stannismod.stellurgy.inventory;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import dev.stannismod.stellurgy.client.ShipReadoutText;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;

/**
 * The flight computer's console: the ship's readout, DESIGN beside LIVE, redrawn from whatever the
 * client last received for this computer.
 */
public class ModuleShipReadout extends ModuleText {

    private final TileAdvancedFlightComputer computer;

    public ModuleShipReadout(int offsetX, int offsetY, TileAdvancedFlightComputer computer) {
        super(offsetX, offsetY, "", 0x404040, 0.5F);
        this.computer = computer;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        setText(String.join("\n", ShipReadoutText.console(computer.clientReadout())));
        super.renderBackground(gui, x, y, mouseX, mouseY, font);
    }
}
