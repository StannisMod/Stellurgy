package dev.stannismod.stellurgy.api;

import dev.stannismod.stellurgy.api.satellite.SatelliteBase;

/**
 * A test's temporary entry in the game's {@link SatelliteRegistry}: registered through the public API,
 * and removed — the previous occupant of the key put back — when the handle closes. The registry is
 * the mod's for the life of the JVM, so an entry a test leaves behind is seen by every later test.
 *
 * <p>In the registry's own package so the removal needs no reflection. Test source set: absent from
 * a released jar.</p>
 */
public final class SatelliteRegistryArrangement implements AutoCloseable {

    private final String key;
    private final Class<? extends SatelliteBase> previous;

    private SatelliteRegistryArrangement(String key, Class<? extends SatelliteBase> previous) {
        this.key = key;
        this.previous = previous;
    }

    /** Register {@code clazz} under {@code key} until the handle closes. */
    public static SatelliteRegistryArrangement registerSatellite(String key, Class<? extends SatelliteBase> clazz) {
        Class<? extends SatelliteBase> previous = SatelliteRegistry.registry.get(key);
        SatelliteRegistry.registerSatellite(key, clazz);
        return new SatelliteRegistryArrangement(key, previous);
    }

    @Override
    public void close() {
        if (previous == null) {
            SatelliteRegistry.registry.remove(key);
        } else {
            SatelliteRegistry.registry.put(key, previous);
        }
    }
}
