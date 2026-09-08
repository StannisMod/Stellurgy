package zmaster587.advancedRocketry.test.trace;

/**
 * What the deck camera was last handed on this client — the half of it a test polls.
 *
 * <p><b>Why a holder and not a record.</b> The camera is set up once per rendered frame, and the
 * value below changes on most of them: a ship rolling under an engaged camera moves its up vector
 * every frame without any edge to record. A per-frame record would turn its own ring over in
 * seconds, and the readers do not want a history — they want "where is the ship's up NOW", which is
 * what a scenario polls while it rolls a craft over and waits for it to pass vertical.</p>
 *
 * <p><b>Why the CLIENT's value and not the server's.</b> The server's {@code ship-info} carries the
 * authoritative attitude quaternion, and three scenarios already derive an up-Y from it. This is
 * deliberately the other one: what the client itself believes, which is what the pilot under test is
 * looking at. Swapping one for the other would not be a migration, it would be a different
 * question — and one of the assertions that reads this says so in its own message.</p>
 *
 * <p>Written by the camera watcher from {@code ShipFrameCamera.recordCamera}'s own {@code shipUp}
 * parameter, so it is the same vector at the same moment production used to store. Production keeps
 * no such field any more.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class DeckCameraState {

    private DeckCameraState() {}

    /**
     * The world Y of the ship's local up as of the last camera setup: +1 upright, 0 on its side,
     * -1 inverted; 1.0 (identity) until one has been seen.
     *
     * <p>Public because it is READ BY NAME across the socket, which is the whole point of it — and
     * because it is a latest-value, the reader gets whatever the client last drew, on a shared
     * client that is whatever ship the camera last engaged for. That was equally true of the
     * production field it replaces; what is new is that it says so.</p>
     */
    public static volatile double shipUpY = 1.0;

    /** Note a camera setup. A null {@code shipUp} leaves the last value standing — production's own
     *  rule, kept: a frame that hands no up vector is not a statement that the ship became upright. */
    public static void noteCamera(double[] shipUp) {
        if (shipUp != null) {
            shipUpY = shipUp[1];
        }
    }
}
