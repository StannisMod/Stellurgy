package dev.stannismod.stellurgy.test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The highest eviction count {@link Events} has ANNOUNCED so far for each record type, so a growing
 * one is said once per ring's worth of growth instead of on every read.
 *
 * <p><b>Its owner is whatever owns the logs being read.</b> A shared-harness class keeps one in its
 * class scope (the base classes' {@code evictionReports()}), because its server and client live as
 * long as the class run; a class that boots its harness per test keeps one per test instance, because
 * that test's server is the only one whose counters it reads. It holds nothing but "what has already
 * been printed": no assertion reads it, and a wrong value costs a duplicate line or a missing one,
 * never a verdict.</p>
 *
 * <p>The server's log and the client's are read through the same instance and count separately, so
 * the blind spot is named rather than engineered away: a growth on the quieter side is masked while
 * the busier side's total is higher. Announcing the busy side is the point.</p>
 */
public final class EvictionReports {

    private final Map<String, Long> reported = new ConcurrentHashMap<>();

    /** The count last announced for {@code type}, or {@code null} when none has been. */
    Long lastAnnounced(String type) {
        return reported.get(type);
    }

    /** Remember that {@code count} has been announced for {@code type}. */
    void announced(String type, long count) {
        reported.merge(type, count, Math::max);
    }
}
