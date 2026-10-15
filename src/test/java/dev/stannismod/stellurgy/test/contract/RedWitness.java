package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.*;

/**
 * This test was seen RED with production broken, and here is what was broken: the record a reviewer
 * re-runs, on the method whose verdict it reddened.
 *
 * <p>One form, the replayable one: {@link #member()}, {@link #at()}, {@link #replacedBy()},
 * {@link #fails()}, {@link #on()} (and {@link #verdict()} when the method has several). The fragment
 * {@code at} occurs once in the member's body, becomes {@code replacedBy} ({@code ""} drops it), and the
 * method then fails with a message starting {@code fails}. A tool applies it, runs the method and
 * restores the file, so anyone can re-run it. The member is a decision of production -- or of the test
 * system's own judges, a list the build names -- never a probe or a harness instrument: the build fails
 * on any other.</p>
 *
 * <p>A record taken before this form existed is a {@link LegacyWitness}; an attempt that left the
 * verdict green is not a witness at all, see {@link NotYetRed}.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(RedWitness.List.class)
public @interface RedWitness {

    /** {@link #replacedBy()}'s default: distinguishes "not given" from {@code ""}, a dropped fragment. */
    String NOT_GIVEN = "\0not given";

    /** Which of the method's verdicts this break reddened, when it has more than one; else empty. */
    String verdict() default "";

    /**
     * The production member broken: {@code Class#member} (an inner class by its own simple name, a
     * constructor as {@code Class#Class}, a field by its name), or a resource and key,
     * {@code assets/stellurgy/lang/en_US.lang#msg.key}.
     */
    String member() default "";

    /** A fragment of the member's body, copied exactly, occurring once in it; whitespace is free. */
    String at() default "";

    /** What the fragment was replaced by; {@code ""} drops it. */
    String replacedBy() default NOT_GIVEN;

    /** The start of the failure message the verdict gave. */
    String fails() default "";

    /** The day the witness was taken, {@code yyyy-mm-dd}. */
    String on() default "";

    /** The container javac builds when a method carries more than one record. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List {
        RedWitness[] value();
    }
}
