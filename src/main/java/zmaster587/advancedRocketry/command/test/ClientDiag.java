package zmaster587.advancedRocketry.command.test;

/**
 * Client-side facts a harness confirms about its OWN client, read back from the game rather than
 * asserted by the code that set them.
 *
 * <p>The distinction is the whole value: "the mute code ran" and "the master volume is actually 0"
 * are different claims, and only the second is worth a test. Each field here is a READBACK — what
 * the game reports after production acted — captured by a test-only mixin, never published by
 * production itself.</p>
 */
public final class ClientDiag {

    /** The real master sound level read back from {@code GameSettings} right after the harness
     *  client is muted, or {@code NaN} until then. */
    public static volatile float testClientMasterVolume = Float.NaN;

    private ClientDiag() { }

    public static void masterVolume(float level) {
        testClientMasterVolume = level;
    }

    /**
     * Deliberately NOT called between scenarios, and the reason is the value's lifetime.
     *
     * <p>This is written once, when the client's proxy comes up, and read by one boot-baseline
     * scenario. It is an observation about THIS CLIENT, not an accumulator belonging to a scenario —
     * so a per-scenario reset would replace the only reading it ever gets with {@code NaN} and the
     * baseline would go red for a reason that has nothing to do with the client's volume. It exists
     * for a caller that wants to re-arm the observation deliberately.</p>
     *
     * <p>Checked 2026-09-13 while giving the pilot-input counters an owner: a reset is correct only
     * where the reader asks an ABSOLUTE question about ONE scenario, and this reader does not.</p>
     */
    public static void reset() {
        testClientMasterVolume = Float.NaN;
    }
}
