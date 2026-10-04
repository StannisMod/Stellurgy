package com.github.stannismod.forge.testing.junit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What one run of one test class shares between its test methods — a booted harness, a plot
 * allocator, a fixture built once — owned by the {@link ClassScopeRunner} running that class.
 *
 * <p>Its lifetime is the class run: the runner creates it, calls {@link #open} before anything of
 * the class runs (outside every {@code @BeforeClass}), hands it to each test instance through
 * {@link ScopedTest#attachScope}, and calls {@link #close} after everything of the class ran,
 * whatever happened. Nothing of it outlives the class, so nothing of it needs a static field — which
 * is what JUnit 4's own static {@code @BeforeClass}/{@code @AfterClass} pair forces on a class that
 * shares state between its methods.</p>
 *
 * <p>A subclass needs a public no-argument constructor; the runner makes one instance per class run.</p>
 */
public abstract class TestClassScope {

    private final Map<Class<?>, Object> memory = new LinkedHashMap<>();

    /**
     * Start what the class shares. A throw here is a failure of the CLASS, and an
     * {@link org.junit.AssumptionViolatedException} skips the class — exactly as the same throw from
     * a {@code @BeforeClass} would.
     */
    protected abstract void open(Class<?> testClass) throws Exception;

    /** Release what {@link #open} started. Called once, after every {@link AutoCloseable} memory. */
    protected abstract void close() throws Exception;

    /**
     * This class run's one instance of {@code type}, created on first use. A memory that is
     * {@link AutoCloseable} is closed when the class run ends, in reverse order of creation and
     * before {@link #close}, so it can still use what {@link #open} started.
     */
    public final synchronized <T> T memory(Class<T> type, Supplier<T> create) {
        Object held = memory.get(type);
        if (held == null) {
            held = create.get();
            memory.put(type, held);
        }
        return type.cast(held);
    }

    /** The runner's release: memories first, newest first, then {@link #close}. */
    final void release() throws Exception {
        List<Object> created;
        synchronized (this) {
            created = new ArrayList<>(memory.values());
            memory.clear();
        }
        Exception deferred = null;
        for (int i = created.size() - 1; i >= 0; i--) {
            Object held = created.get(i);
            if (held instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) held).close();
                } catch (Exception e) {
                    if (deferred == null) deferred = e;
                    else deferred.addSuppressed(e);
                }
            }
        }
        try {
            close();
        } catch (Exception e) {
            if (deferred == null) deferred = e;
            else deferred.addSuppressed(e);
        }
        if (deferred != null) {
            throw deferred;
        }
    }
}
