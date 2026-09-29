package zmaster587.advancedRocketry.test.trace;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.function.Supplier;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.Container;

/**
 * What an edge recorder remembers about ONE body, held by that body — implemented on {@code Entity} by
 * {@code MixinEntityTraceMemory}.
 *
 * <p>A recorder that writes "this changed" needs the previous value per body. That memory used to
 * live in static weak maps keyed by the entity, one per recorder, for the life of the JVM; the entity
 * is the owner the key was standing in for, and memory kept on it is created with the body and gone
 * with it. One lazily-created slot per entity, one memory object per recorder class in it.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public interface EntityTrace {

    /** This body's one instance of a recorder's memory, created on first use. */
    <T> T arTest$memory(Class<T> type, Supplier<T> create);

    /** {@link #arTest$memory} on {@code entity}. */
    static <T> T memory(Entity entity, Class<T> type, Supplier<T> create) {
        return ((EntityTrace) entity).arTest$memory(type, create);
    }

    /** The deck-gate recorders' memory: the verdict last recorded, and whether the body was on the
     *  ground at the last travel return. */
    final class DeckGate {
        /** {@code (worldTime << 2) | verdict bits} of the last {@code deck_gate_decided}, or -1. */
        public long lastGateRecord = -1L;
        /** Whether the body was grounded at the previous travel return; null before the first. */
        public Boolean groundedAtLastTravel;
    }

    /** The suit-immunity recorder's memory: the last decision recorded, per atmosphere. */
    final class SuitDecisions {
        public final HashMap<String, Boolean> byAtmosphere = new HashMap<>();
    }

    /** The container-interaction recorder's memory: the container last checked and the answer. */
    final class ContainerInteract {
        public WeakReference<Container> container;
        public Boolean allowed;
    }
}
