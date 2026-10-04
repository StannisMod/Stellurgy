package dev.stannismod.stellurgy.test.trace;

import java.util.HashMap;
import java.util.Map;

/**
 * What each retried crossing placement last said — the server's, kept in its {@link SideTrace} — so a
 * placement retried every tick is recorded when its REASON changes and not on every tick.
 *
 * <p>A crew placement is retried every server tick until the re-assembled ship has a seat to offer,
 * and an arrival can wait tens of ticks for that. Recording every refusal would fill the event ring
 * for that type and make a failure's chain dump hundreds of identical lines long; recording only the
 * first would hide a placement whose reason CHANGED mid-way, which is the diagnosis.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class CrossingMemory {

    private final Map<String, String> lastBlock = new HashMap<>();

    /** The memory of the server this thread runs. */
    public static CrossingMemory here() {
        return SideTrace.here().memory(CrossingMemory.class, CrossingMemory::new);
    }

    /**
     * Whether {@code block} is a NEW reason for {@code key} — {@code true} the first time and every
     * time it differs from what was last noted. The caller records only when this says so.
     *
     * @param key   the leg and the ship it is about, e.g. {@code "reseat:" + shipId}
     * @param block the placement's own account of what it is waiting on
     */
    public synchronized boolean noteBlocked(String key, String block) {
        String value = block == null ? "" : block;
        String was = lastBlock.put(key, value);
        return was == null || !was.equals(value);
    }

    /** Forget a ship's last reason once its placement has succeeded, so a later jump starts clean. */
    public synchronized void clear(String key) {
        lastBlock.remove(key);
    }
}
