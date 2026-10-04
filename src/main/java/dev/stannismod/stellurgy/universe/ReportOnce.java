package dev.stannismod.stellurgy.universe;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The layout problems one galaxy has already reported.
 *
 * <p>The body derivation runs on every query — the console's forecast once a second, the render
 * broadcast, the entry resolver, every probe — so an unguarded report is a flood, not a diagnostic: a
 * 28-minute playtest produced 28,061 clamp warnings and drowned the log it was needed in. One report
 * per distinct problem per galaxy, and a galaxy that is dropped takes its memory with it, so the next
 * world in the same JVM has its own faults reported.</p>
 *
 * <p>Owned by the galaxy ({@code DimensionManager#reports}) and handed to the deriving code by its
 * caller.</p>
 */
public final class ReportOnce {

    private final Set<String> reported = Collections.synchronizedSet(new HashSet<String>());

    /** Whether {@code key} is reported here for the first time; the caller says it only then. */
    public boolean first(String key) {
        return reported.add(key);
    }
}
