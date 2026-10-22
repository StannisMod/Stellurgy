package dev.stannismod.stellurgy.inventory.modules;

import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.item.ItemSatelliteIdentificationChip;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.tile.satellite.TerminalReadout;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteTerminal;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collections;
import java.util.List;

/**
 * The Satellite Control Center's status: what its last try came to and what its last contact brought.
 * Drawn from the terminal's own copy on the client, which the server updates whenever either changes —
 * the screen never computes a status of its own.
 */
public class ModuleSatelliteTerminal extends ModuleBase {

    private final ModuleText text;
    private final int color;
    private final IInventory inv;
    private final TileSatelliteTerminal tile;

    public ModuleSatelliteTerminal(int x, int y, int color, IInventory inv, TileSatelliteTerminal tile) {
        super(x, y);
        this.color = color;
        this.inv = inv;
        this.tile = tile;
        this.text = new ModuleText(x, y, "", color);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        text.setText(statusText());
        text.setColor(color);
        text.renderBackground(gui, x, y, mouseX, mouseY, font);
    }

    @Override
    public List<Slot> getSlots(Container container) { return Collections.emptyList(); }

    /** The lines the screen shows: the satellite's name, the outcome, and the last contact. */
    private String statusText() {
        StringBuilder msg = new StringBuilder();
        String satName = chipSatelliteName();
        if (satName != null && !satName.isEmpty()) {
            msg.append(satName).append("\n\n");
        }
        TileSatelliteTerminal.Outcome outcome = tile.outcome();
        msg.append(LibVulpes.proxy.getLocalizedString(outcomeKey(outcome)));
        if (outcome == TileSatelliteTerminal.Outcome.NO_SATELLITE) {
            return msg.toString();
        }
        TerminalReadout.Contact last = tile.readout().last();
        msg.append("\n").append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.power")).append(" ")
                .append(last.powerPerTick);
        if (last.capacity > 0) {
            msg.append("\n").append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.data")).append(" ")
                    .append(last.aboard).append("/").append(last.capacity);
        }
        msg.append("\n").append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.received")).append(" ")
                .append(last.received).append(", ")
                .append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.lost")).append(" ").append(last.lostAboard);
        if (last.partsFailed > 0) {
            msg.append("\n").append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.partsfailed")).append(" ")
                    .append(last.partsFailed);
        }
        return msg.toString();
    }

    private String chipSatelliteName() {
        if (inv == null || inv.getSizeInventory() == 0) {
            return null;
        }
        ItemStack stack0 = inv.getStackInSlot(0);
        if (stack0.isEmpty() || !(stack0.getItem() instanceof ItemSatelliteIdentificationChip)) {
            return null;
        }
        SatelliteBase sat = ItemSatelliteIdentificationChip.getSatellite(stack0);
        return sat == null ? null : sat.getName();
    }

    /** The lang key each outcome is shown by. */
    static String outcomeKey(TileSatelliteTerminal.Outcome outcome) {
        switch (outcome) {
            case NO_POWER:
                return "msg.notenoughpower";
            case OUT_OF_RANGE:
                return "msg.satctrlcenter.toofar";
            case NOT_IN_ORBIT:
                return "msg.satctrlcenter.notinorbit";
            case NO_PLACE:
                return "msg.satctrlcenter.noplace";
            case OUT_OF_SIGHT:
                return "msg.satctrlcenter.outofsight";
            case SILENT:
                return "msg.satctrlcenter.silent";
            case NOTHING_NEW:
                return "msg.satctrlcenter.nothingnew";
            case RECEIVED:
                return "msg.satctrlcenter.contact";
            default:
                return "msg.satctrlcenter.nolink";
        }
    }
}
