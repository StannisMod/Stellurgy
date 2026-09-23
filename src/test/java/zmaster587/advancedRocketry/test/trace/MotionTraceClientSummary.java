package zmaster587.advancedRocketry.test.trace;

import zmaster587.advancedRocketry.command.test.MotionTrace;

/**
 * The client half of the flight recorder, published as a RECORD at the moment a test asks.
 *
 * <p>The summary is rendered from {@link MotionTrace}'s client rings over trailing wall-clock
 * windows, so WHEN it is taken is part of what it says. It used to be read across the socket as the
 * {@code toString()} of a static field ({@code MotionTrace.CLIENT_SUMMARY}), which the reading could
 * not attribute to a moment; now the render happens on the client thread inside
 * {@link #record()} and lands in the client log as {@code motion_trace_client_summary}, after the
 * reader's own mark.</p>
 *
 * <p>The summary is JSON and a record field is a string, so its double quotes travel as single
 * quotes ({@link TestTrace#json}); the summary carries no apostrophes of its own, so a reader turns
 * them back. Test source set: absent from a released jar.</p>
 */
public final class MotionTraceClientSummary {

    private MotionTraceClientSummary() {}

    /** Render the client summary now and record it. Invoked through the static-invoke bridge. */
    public static int record() {
        TestTrace.recordHere("motion_trace_client_summary",
                "\"summary\":\"" + TestTrace.json(MotionTrace.clientSummary()) + "\"");
        return 0;
    }
}
