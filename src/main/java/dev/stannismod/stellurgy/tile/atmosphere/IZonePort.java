package dev.stannismod.stellurgy.tile.atmosphere;

import dev.stannismod.stellurgy.api.util.IBlobHandler;

/**
 * A block that anchors a zone — the oxygen vent and the ventilation port.
 */
public interface IZonePort extends IBlobHandler {

    /** Whether the room this block anchors is a sealed zone right now. */
    boolean isSealed();

    /**
     * Run this block's seal check now — the check its own cadence runs every few seconds. The flood
     * fill may run off-thread, so a room that is closed can answer "not yet" until a later check.
     *
     * @return whether the room is sealed after the check
     */
    boolean checkSealNow();
}
