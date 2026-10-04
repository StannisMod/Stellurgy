package dev.stannismod.stellurgy.client;

import dev.stannismod.stellurgy.api.FreeFlightInput;

/**
 * What the pilot is commanding a craft to do, as this client holds it: the flight cursor, the turn
 * rates the HUD draws, and the last input sent to the server.
 *
 * <p>Owned by the client copy of what is piloted — the {@code EntityRocket}, or a ship's
 * {@code TileAdvancedFlightComputer} — because the command is written into that craft; the server's
 * copy of the craft is the authority, and this is the pilot's side of it. A plain class with no
 * client types, because a craft is common code and holds one on both sides; only the client writes
 * it.</p>
 */
public final class PilotCommand {

    /** Flight-cursor deflection in [-1,1]² (X = roll, Y = pitch); stays where the mouse leaves it. */
    float cursorX;
    float cursorY;
    /** The previous tick's deflection, so the HUD can interpolate the dot between ticks. */
    float prevCursorX;
    float prevCursorY;
    /** The commanded turn rates of the current tick, [-1,1], for the HUD turn-rate dot. */
    float yawRate;
    float pitchRate;
    /** The last input sent to the server; a craft resends only when the intent changes. */
    FreeFlightInput lastSent = FreeFlightInput.zero();

    /** Centre the cursor and forget what was sent: the next tick starts a fresh flight. */
    void reset() {
        cursorX = 0f;
        cursorY = 0f;
        prevCursorX = 0f;
        prevCursorY = 0f;
        lastSent = FreeFlightInput.zero();
    }

    /** The cursor deflection interpolated across the frame, X. */
    public float cursorX(float partialTicks) {
        return prevCursorX + (cursorX - prevCursorX) * partialTicks;
    }

    /** The cursor deflection interpolated across the frame, Y. */
    public float cursorY(float partialTicks) {
        return prevCursorY + (cursorY - prevCursorY) * partialTicks;
    }
}
