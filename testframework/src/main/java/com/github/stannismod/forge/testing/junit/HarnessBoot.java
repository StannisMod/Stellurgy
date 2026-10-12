package com.github.stannismod.forge.testing.junit;

/**
 * The right to start a harness server or client of one's own — what every {@code start…} of
 * {@code RealDedicatedServerHarness} and {@code RealClientHarness} takes as its first argument.
 *
 * <p><b>Who can hold one.</b> The constructor is package-private, so only the bases of this package make
 * one: the mechanics bases ({@link AbstractHeadlessServerTest}, {@link AbstractClientE2ETest}) for their
 * own boots, {@link AbstractMechanicsTest#harnessBoot()} and {@link TestClassScope#harnessBoot()} for a
 * mechanics class or scope that starts its own, and {@link AbstractPlayerE2ETest} for the e2e pair it
 * starts itself. An e2e extends {@link AbstractPlayerE2ETest}, which hands out none — so a second server
 * or client started from an e2e, where the e2e's input and reads would not hold, does not compile.</p>
 *
 * <p><b>What the type cannot stop, refused at the construction.</b> A class nested in an e2e that extends
 * a mechanics base can reach that base's protected factory. So the constructor also walks its own call
 * stack and refuses, loudly, when any frame belongs to a strict subclass of {@link AbstractPlayerE2ETest}:
 * a token asked for anywhere below an e2e's own code — its test method, a helper it calls, a nested
 * class's method — is refused, while the e2e base's own start (whose frames are the base's) is not. A
 * frame whose class cannot be resolved by name (a lambda's generated class) is passed over: its body is a
 * method of the class that wrote the lambda, and that frame is on the stack too.</p>
 *
 * <p>What it is silent about: work an e2e hands to ANOTHER THREAD carries none of the e2e's frames, and
 * {@code null} passed where a token is wanted compiles — the harness starts refuse a null token at the
 * call.</p>
 */
public final class HarnessBoot {

    HarnessBoot() {
        refuseBelowAnE2E();
    }

    /** Refuses {@code boot} when it is null: a start without a real token is not a start anybody granted. */
    public static HarnessBoot require(HarnessBoot boot) {
        if (boot == null) {
            throw new IllegalArgumentException("a harness start needs a HarnessBoot, which only the mechanics"
                    + " bases of com.github.stannismod.forge.testing.junit make; null is not one");
        }
        return boot;
    }

    private static void refuseBelowAnE2E() {
        ClassLoader loader = HarnessBoot.class.getClassLoader();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            Class<?> owner;
            try {
                owner = Class.forName(frame.getClassName(), false, loader);
            } catch (ClassNotFoundException | LinkageError unresolvable) {
                continue;
            }
            if (owner != AbstractPlayerE2ETest.class && AbstractPlayerE2ETest.class.isAssignableFrom(owner)) {
                throw new IllegalStateException("REFUSED: a harness start was asked for below the e2e "
                        + owner.getName() + " (at " + frame + "). An e2e walks a player's path on the one"
                        + " server and client its base started in e2e mode; a second one, started outside"
                        + " that mode, would hand it the verbs the e2e base withholds.");
            }
        }
    }
}
