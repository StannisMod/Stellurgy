package com.github.stannismod.forge.testing.junit;

/**
 * A test class that receives its class run's {@link TestClassScope}. {@link ClassScopeRunner} calls
 * {@link #attachScope} on every instance it creates, before any rule or {@code @Before} of that
 * instance runs.
 */
public interface ScopedTest<S extends TestClassScope> {

    void attachScope(S scope);
}
