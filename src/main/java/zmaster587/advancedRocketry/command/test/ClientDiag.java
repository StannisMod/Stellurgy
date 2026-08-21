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

    public static void reset() {
        testClientMasterVolume = Float.NaN;
    }
}
