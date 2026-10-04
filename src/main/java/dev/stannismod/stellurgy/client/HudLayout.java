package dev.stannismod.stellurgy.client;

/**
 * Where each element of the suit HUD stands, as a function of the screen it is drawn on.
 *
 * <p>Layout belongs to the screen, not to the mod: only the code drawing a frame knows the screen's
 * size, so a position is computed there, from that size, every time it is drawn. Nothing here is
 * remembered between frames, and every placement is kept as an anchor plus offsets from it — never
 * as a coordinate tuned for one screen.</p>
 */
public final class HudLayout {

    /** Pinned top-left: distance from the left edge. */
    private static final int SUIT_PANEL_FROM_LEFT = 8;
    /** Pinned top-left: distance from the top edge. */
    private static final int SUIT_PANEL_FROM_TOP = 8;
    /** Offset to the right of the screen's centre. */
    private static final int OXYGEN_BAR_FROM_CENTRE_X = 8;
    /** Distance above the screen's bottom edge. */
    private static final int OXYGEN_BAR_FROM_BOTTOM = 57;
    private static final int HYDROGEN_BAR_FROM_CENTRE_X = 8;
    private static final int HYDROGEN_BAR_FROM_BOTTOM = 74;
    /** Pinned bottom-left: distance from the left edge. */
    private static final int ATMOSPHERE_BAR_FROM_LEFT = 8;
    private static final int ATMOSPHERE_BAR_FROM_BOTTOM = 27;

    private HudLayout() {
    }

    public static int suitPanelX(int screenWidth) {
        return SUIT_PANEL_FROM_LEFT;
    }

    public static int suitPanelY(int screenHeight) {
        return SUIT_PANEL_FROM_TOP;
    }

    public static int oxygenBarX(int screenWidth) {
        return screenWidth / 2 + OXYGEN_BAR_FROM_CENTRE_X;
    }

    public static int oxygenBarY(int screenHeight) {
        return screenHeight - OXYGEN_BAR_FROM_BOTTOM;
    }

    public static int hydrogenBarX(int screenWidth) {
        return screenWidth / 2 + HYDROGEN_BAR_FROM_CENTRE_X;
    }

    public static int hydrogenBarY(int screenHeight) {
        return screenHeight - HYDROGEN_BAR_FROM_BOTTOM;
    }

    public static int atmosphereBarX(int screenWidth) {
        return ATMOSPHERE_BAR_FROM_LEFT;
    }

    public static int atmosphereBarY(int screenHeight) {
        return screenHeight - ATMOSPHERE_BAR_FROM_BOTTOM;
    }
}
