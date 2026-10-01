package dev.stannismod.stellurgy.api.fuel;

import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What every {@link FuelType} held when a test began, put back exactly when the handle closes. The
 * fuels live on the enum constants, which are the game's for the life of the JVM, so a fuel a test
 * registers would otherwise be a fuel for every later test.
 *
 * <p>In the registry's own package so the restore needs no reflection. Test source set: absent from
 * a released jar.</p>
 */
public final class FuelRegistrations implements AutoCloseable {

    private final Map<FuelType, Set<Object>> before = new EnumMap<>(FuelType.class);

    private FuelRegistrations() {
        for (FuelType type : FuelType.values()) {
            before.put(type, new HashSet<Object>(type.fuels));
        }
    }

    /** Remember every fuel type's current entries, to be restored by {@link #close()}. */
    public static FuelRegistrations remember() {
        return new FuelRegistrations();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void close() {
        for (Map.Entry<FuelType, Set<Object>> e : before.entrySet()) {
            Set fuels = e.getKey().fuels;
            fuels.retainAll(e.getValue());
        }
    }
}
