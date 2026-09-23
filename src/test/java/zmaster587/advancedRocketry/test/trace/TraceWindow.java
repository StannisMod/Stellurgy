package zmaster587.advancedRocketry.test.trace;

/**
 * A window a test opened on one side: an object the scenario owns, registered with that side's
 * {@link SideTrace} under a handle. Its accumulators are instance fields, so two windows never share
 * a count and a closed one is gone.
 *
 * <p>A marker and nothing more: each kind has its own {@code peek}/{@code close} record, and a
 * common method would have to pretend they are one shape. The bridge addresses a kind by its static
 * {@code open(...)}, {@code peek(int handle)} and {@code close(int handle)}.</p>
 */
public interface TraceWindow {
}
