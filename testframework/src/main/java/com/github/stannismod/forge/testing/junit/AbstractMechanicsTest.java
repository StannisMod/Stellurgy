package com.github.stannismod.forge.testing.junit;

/**
 * The base of a mechanics test that starts its own harness — a server it restarts against the same
 * world, a pair it boots in a mode no shared base offers. All it gives is {@link #harnessBoot()}, the
 * token every harness start takes; the test manages what it starts.
 *
 * <p>An e2e never extends this: it extends {@link AbstractPlayerE2ETest}, whose children are the only
 * ones it walks on. See {@link HarnessBoot} for what holds that when a nested class tries; the build's test
 * bindings check refuses any e2e whose code reaches this class at all.</p>
 */
public abstract class AbstractMechanicsTest {

    /**
     * The right to start a harness server or client of this test's own. An INSTANCE method, and final: the
     * token belongs to a running mechanics test, so a static helper elsewhere cannot hand it out without
     * one in hand.
     */
    protected final HarnessBoot harnessBoot() {
        return new HarnessBoot();
    }
}
