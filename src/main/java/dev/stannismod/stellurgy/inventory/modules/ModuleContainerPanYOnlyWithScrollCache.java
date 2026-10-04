package dev.stannismod.stellurgy.inventory.modules;

import dev.stannismod.stellurgy.libvulpes.inventory.GuiModular;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleContainerPanYOnly;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.List;

/**
 * A vertically scrolling list that reopens where it was left, remembered by the machine that owns the
 * list ({@link ScrollMemory}), and that the mouse wheel scrolls while the pointer is over it.
 */
@SideOnly(Side.CLIENT)
public class ModuleContainerPanYOnlyWithScrollCache extends ModuleContainerPanYOnly {

    private final ScrollMemory memory;

    // >>> Store GUI origin captured during render to avoid touching protected xSize/ySize
    private volatile int lastGuiLeft = 0, lastGuiTop = 0;

    // ===== Instance state =====
    private int lastSavedY = ScrollMemory.NONE;
    private boolean didRestore = false;

    // Debounce to avoid micro-stutter under heavy input
    private long lastSaveNs = 0L;
    private static final long SAVE_INTERVAL_NS = 30_000_000L; // 30 ms

    /** The last wheel notch this list took, so a notch delivered twice in one tick scrolls once. */
    private int lastWheelTick = -1;
    private int lastWheelSign = 0; // -1/+1

    public ModuleContainerPanYOnlyWithScrollCache(
            int offsetX, int offsetY,
            List<ModuleBase> moduleList, List<ModuleBase> staticModules,
            ResourceLocation backdrop,
            int screenSizeX, int screenSizeY,
            int paddingX, int paddingY,
            int containerSizeX, int containerSizeY,
            ScrollMemory memory
    ) {
        super(offsetX, offsetY, moduleList, staticModules, backdrop,
              screenSizeX, screenSizeY, paddingX, paddingY,
              containerSizeX, containerSizeY);
        this.memory = memory;
    }

    @Override
    public void setEnabled(boolean state) {
        if (!state && this.isEnabled()) {
            saveScrollIfChangedForce(); // persist last position
        }
        super.setEnabled(state);
    }

    // Clamp to base’s legal range [-containerSizeY, 0]
    private int clampScroll(int y) {
        if (y > 0) return 0;
        int min = -this.containerSizeY;
        return (y < min) ? min : y;
    }

    // Debounced save after movement, skipping no-op writes
    private void saveScrollIfChanged() {
        final int y = clampScroll(super.getScrollY());

        if (y == lastSavedY) return;

        if (memory.offset == y) {
            lastSavedY = y;
            return;
        }

        // Keep debounce to avoid bursts during fine-grained drags.
        final long now = System.nanoTime();
        if (now - lastSaveNs < SAVE_INTERVAL_NS) {
            lastSavedY = y;    // remember the new y even if we didn't write it yet
            return;
        }

        lastSaveNs = now;
        lastSavedY = y;
        memory.offset = y;
    }

    // Force save (bypass debounce) on close or disable, still skipping no-op
    private void saveScrollIfChangedForce() {
        final int y = clampScroll(super.getScrollY());
        if (y == lastSavedY && memory.offset == y) return;
        lastSavedY = y;
        lastSaveNs = System.nanoTime();
        memory.offset = y;
    }

    // Restore once when bounds are stable
    @Override
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        this.lastGuiLeft = x;
        this.lastGuiTop  = y;

        if (!didRestore) {
            int v = memory.offset;
            if (v != ScrollMemory.NONE) {
                int clamped = clampScroll(v);
                super.setOffset2(-clamped);   // base uses -y
                lastSavedY = clamped;
            }
            didRestore = true;
        }
        super.renderBackground(gui, x, y, mouseX, mouseY, font);
    }

    // Single save point for any movement
    @Override
    protected void moveContainerInterior(int deltaY) {
        super.moveContainerInterior(deltaY);
        saveScrollIfChanged();
    }

    private boolean isMouseOverThis(int relX, int relY) {
        // relX/relY are GUI-relative to (guiLeft, guiTop)
        int localX = relX - this.offsetX;
        int localY = relY - this.offsetY;
        return localX >= 0 && localX < this.screenSizeX
            && localY >= 0 && localY < this.screenSizeY;
    }

    /**
     * Hands a mouse-wheel notch over a modular GUI to the scrolling list under the pointer. Registered
     * once, at client init; it holds nothing — the lists it routes to are the open screen's own.
     */
    public static final class WheelRouter {

        @SubscribeEvent
        public void onMouseInputPre(GuiScreenEvent.MouseInputEvent.Pre evt) throws IOException {
            GuiScreen screen = evt.getGui();
            if (!(screen instanceof GuiModular)) return;

            int d = org.lwjgl.input.Mouse.getEventDWheel();
            if (d == 0) return;

            Minecraft mc = Minecraft.getMinecraft();
            int tick = (mc.ingameGUI != null) ? mc.ingameGUI.getUpdateCounter() : 0;
            int sign = Integer.signum(d);

            int scaledW = screen.width, scaledH = screen.height;
            int mouseX = org.lwjgl.input.Mouse.getX() * scaledW / mc.displayWidth;
            int mouseY = scaledH - org.lwjgl.input.Mouse.getY() * scaledH / mc.displayHeight - 1;

            List<ModuleBase> modules = ((GuiModular) screen).modules();
            for (int i = modules.size() - 1; i >= 0; i--) {
                if (!(modules.get(i) instanceof ModuleContainerPanYOnlyWithScrollCache)) continue;
                ModuleContainerPanYOnlyWithScrollCache list = (ModuleContainerPanYOnlyWithScrollCache) modules.get(i);
                if (!list.getVisible() || !list.isEnabled()) continue;
                if (!list.isMouseOverThis(mouseX - list.lastGuiLeft, mouseY - list.lastGuiTop)) continue;

                // Coalesce: the same notch delivered twice in one tick scrolls once.
                if (tick != list.lastWheelTick || sign != list.lastWheelSign) {
                    list.onScroll(d);     // will call moveContainerInterior -> save
                    list.lastWheelTick = tick;
                    list.lastWheelSign = sign;
                }
                evt.setCanceled(true);
                return;
            }
        }
    }
}
