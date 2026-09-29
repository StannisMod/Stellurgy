package zmaster587.advancedRocketry.test.mixin;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import zmaster587.advancedRocketry.test.trace.EntityTrace;

/**
 * Gives every body a slot for what the test recorders remember about it — see {@link EntityTrace}.
 * Null until a recorder first asks, so a body no recorder looks at carries one null reference.
 *
 * <p>Both sides. Test source set.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityTraceMemory implements EntityTrace {

    @Unique
    private Map<Class<?>, Object> arTest$traceMemory;

    @Override
    public <T> T arTest$memory(Class<T> type, Supplier<T> create) {
        if (arTest$traceMemory == null) {
            arTest$traceMemory = new HashMap<>(2);
        }
        Object m = arTest$traceMemory.get(type);
        if (m == null) {
            m = create.get();
            arTest$traceMemory.put(type, m);
        }
        return type.cast(m);
    }
}
