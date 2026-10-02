package dev.stannismod.stellurgy.integration.vs;

/**
 * A body's own ship-frame capture, carried by the {@code Entity} object it describes, so it lives and
 * dies with that object and two objects can never share one.
 *
 * <p>Implemented on every {@code Entity} by a mixin; read and written only by {@link ShipFrameTravel}.
 * An {@code EntityPlayerMP} keeps its object across a dimension change, so the capture follows the
 * body rather than the world it was in.</p>
 */
public interface ShipFrameBody {

    /** The open capture episode, or {@code null} when the body is not resolved in a ship's frame. */
    ShipFrameTravel.ShipFrameState stellurgy$shipFrame();

    void stellurgy$setShipFrame(ShipFrameTravel.ShipFrameState state);

    /** The stamp of this body's most recent capture install; {@code 0} before the first. */
    long stellurgy$captureEpoch();

    void stellurgy$setCaptureEpoch(long epoch);
}
