package dev.stannismod.stellurgy.inventory.modules;

import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiModular;
import dev.stannismod.stellurgy.libvulpes.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IButtonInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleButton;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.satellite.HeldModel;
import dev.stannismod.stellurgy.tile.satellite.TerminalReadout;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteTerminal;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The terminal's second page: what its last contact brought, then the downloaded rows as a table of
 * time, place on the orbit and value, newest first, a page at a time. Drawn from the terminal's own copy
 * on the client; which page is shown belongs to this screen alone.
 */
public class ModuleSatelliteReadout extends ModuleBase implements IButtonInventory {

    /** Rows on one page: what fits under the summary in the screen's height. */
    static final int ROWS_PER_PAGE = 10;

    private static final int BUTTON_OLDER = 0;
    private static final int BUTTON_NEWER = 1;

    private final TileSatelliteTerminal tile;
    private final ModuleText text;
    private final int color;
    private final ModuleButton[] buttons;
    /** Pages back from the newest. */
    private int page;

    public ModuleSatelliteReadout(int x, int y, int color, TileSatelliteTerminal tile) {
        super(x, y);
        this.tile = tile;
        this.color = color;
        this.text = new ModuleText(x, y, "", color);
        this.buttons = new ModuleButton[]{
                new ModuleButton(x + 104, y - 19, BUTTON_OLDER, "<", this, TextureResources.buttonBuild,
                        LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.older"), 24, 16),
                new ModuleButton(x + 132, y - 19, BUTTON_NEWER, ">", this, TextureResources.buttonBuild,
                        LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.newer"), 24, 16)
        };
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        int pages = tile.readout().pages(ROWS_PER_PAGE);
        page = Math.max(0, Math.min(pages - 1, page + (buttonId == BUTTON_OLDER ? 1 : -1)));
    }

    @Override
    public void onMouseClicked(GuiModular gui, int x, int y, int button) {
        super.onMouseClicked(gui, x, y, button);
        for (ModuleButton b : buttons) b.onMouseClicked(gui, x, y, button);
    }

    @Override
    public List<GuiButton> addButtons(int x, int y) {
        List<GuiButton> list = super.addButtons(x, y);
        for (ModuleButton b : buttons) list.addAll(b.addButtons(x, y));
        return list;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void actionPerform(GuiButton button) {
        super.actionPerform(button);
        for (ModuleButton b : buttons) b.actionPerform(button);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderForeground(int guiOffsetX, int guiOffsetY, int mouseX, int mouseY, float zLevel,
                                 GuiContainer gui, FontRenderer font) {
        super.renderForeground(guiOffsetX, guiOffsetY, mouseX, mouseY, zLevel, gui, font);
        for (ModuleButton b : buttons) b.renderForeground(guiOffsetX, guiOffsetY, mouseX, mouseY, zLevel, gui, font);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        for (ModuleButton b : buttons) b.renderBackground(gui, x, y, mouseX, mouseY, font);
        text.setText(tableText());
        text.setColor(color);
        text.renderBackground(gui, x, y, mouseX, mouseY, font);
    }

    @Override
    public List<Slot> getSlots(Container container) { return Collections.emptyList(); }

    /** The summary of the last contact, then this page of rows, newest first. */
    String tableText() {
        TerminalReadout readout = tile.readout();
        TerminalReadout.Contact last = readout.last();
        StringBuilder out = new StringBuilder();
        out.append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.lastcontact")).append("\n");
        out.append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.received")).append(" ").append(last.received)
                .append(", ").append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.lost")).append(" ")
                .append(last.lostAboard).append(", ")
                .append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.partsfailed")).append(" ")
                .append(last.partsFailed).append("\n");
        if (last.missed > 0) {
            out.append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.missed")).append(" ").append(last.missed)
                    .append("\n");
        }
        out.append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.columns")).append("\n");
        for (TerminalReadout.Row row : readout.page(page, ROWS_PER_PAGE)) {
            HeldModel.Region region = row.region();
            if (row.fromProbe) {
                out.append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.row.probe")).append(" ");
            }
            out.append(row.tick).append("  ")
                    .append(Math.round(Math.toDegrees(row.phaseRad))).append("°  ")
                    .append(String.format(Locale.ROOT, "%+.0f° %+.0f°", region.centreNorthDeg(), region.centreEastDeg()))
                    .append("  ").append(valueOf(row));
            Optional<HeldModel.Expectation> expected = readout.expectationOf(row);
            if (expected.isPresent()) {
                out.append("  ").append(expectationOf(expected.get(), row.gyPerS().getAsDouble()));
            }
            out.append("\n");
        }
        if (readout.rows().isEmpty()) {
            out.append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.norows"));
        }
        return out.toString();
    }

    /** What the held readings expect, from where, and the reading's residual against it. */
    private static String expectationOf(HeldModel.Expectation expected, double gyPerS) {
        String basis = LibVulpes.proxy.getLocalizedString(expected.basis == HeldModel.Basis.REGION
                ? "msg.satctrlcenter.basis.region" : "msg.satctrlcenter.basis.orbit");
        StringBuilder out = new StringBuilder();
        out.append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.expected")).append(" ")
                .append(String.format(Locale.ROOT, "%.2e", expected.gyPerS));
        if (expected.standardError().isPresent()) {
            out.append(String.format(Locale.ROOT, " ±%.1e", expected.standardError().getAsDouble()));
        }
        out.append(" (").append(basis).append("), ")
                .append(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.residual")).append(" ")
                .append(String.format(Locale.ROOT, "%+.2e", expected.residualOf(gyPerS)));
        return out.toString();
    }

    private static String valueOf(TerminalReadout.Row row) {
        switch (row.kind) {
            case PART_FAILED:
                return LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.row.partfailed") + " " + row.slot;
            case DATA_OVERFLOWED:
                return LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.row.overflow") + " " + row.count;
            case DOSE_RATE:
                return row.gyPerS().isPresent()
                        ? String.format(Locale.ROOT, "%.2e Gy/s", row.gyPerS().getAsDouble())
                        : LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.row.absent");
            default:
                return LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.row.lost") + " " + row.count;
        }
    }
}
