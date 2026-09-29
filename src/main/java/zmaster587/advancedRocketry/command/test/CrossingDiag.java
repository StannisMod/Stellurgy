package zmaster587.advancedRocketry.command.test;

import java.util.HashMap;
import java.util.Map;

/**
 * The one piece of state the crossing event recorders keep: what each retried placement last said,
 * so a placement that is retried every tick is recorded when its REASON changes and not on every tick.
 *
 * <h2>Why a store, and why here</h2>
 *
 * <p>A crew placement is retried every server tick until the re-assembled ship has a seat to offer,
 * and an arrival can wait tens of ticks for that. Recording every refusal would fill the event ring
 * for that type and make a failure's chain dump hundreds of identical lines long; recording only the
 * first would hide a placement whose reason CHANGED mid-way, which is the diagnosis. So a refusal is
 * recorded when it differs from the last one recorded for the same ship and leg.</p>
 *
 * <p>A test mixin may not carry a non-private static field, and this class sits in the probe tree
 * for the same reason every other recorder store does: both a server probe and the mixins that feed
 * it can reach it, and none of it is referenced from a production path. Nothing here decides
 * anything; it is bookkeeping for an observer.</p>
 */
public final class CrossingDiag {

    private static final Object LOCK = new Object();
    private static final Map<String, String> LAST_BLOCK = new HashMap<>();

    private CrossingDiag() {}

    /**
     * Whether {@code block} is a NEW reason for {@code key} — {@code true} the first time and every
     * time it differs from what was last noted. The caller records only when this says so.
     *
     * @param key   the leg and the ship it is about, e.g. {@code "reseat:" + shipId}
     * @param block the placement's own account of what it is waiting on
     */
    public static boolean noteBlocked(String key, String block) {
        String value = block == null ? "" : block;
        synchronized (LOCK) {
            String was = LAST_BLOCK.put(key, value);
            return was == null || !was.equals(value);
        }
    }

    /** Forget a ship's last reason once its placement has succeeded, so a later jump starts clean. */
    public static void clear(String key) {
        synchronized (LOCK) {
            LAST_BLOCK.remove(key);
        }
    }
}
