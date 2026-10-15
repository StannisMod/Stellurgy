package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.*;

/**
 * A wait that is not a LINK, claimed where its method is declared: the shape it has, the place it stands
 * and why no published record answers it. A wait is a link on a record, or one of the shapes below; there is
 * no poll, and no default shape.
 *
 * <p><b>Where it stands.</b> {@link #at()} is a fragment of the method's source -- whitespace aside, the
 * text of the line the wait or the loop starts on, longer where that is not unique -- occurring EXACTLY ONCE
 * in the method. The claim names that line: a wait elsewhere in the method is not claimed by it. A claim
 * for a wait inside a lambda sits on the method that writes the lambda.</p>
 *
 * <p><b>What the build holds</b> ({@code checkTestBindings}, {@code WaitLoops}): every loop of the test
 * source whose own instructions advance a game clock -- a harness or {@code GameTicks} advance, an
 * {@code Events} step, or a test method that does either -- stands on a line a {@code @Wait} of its method
 * names; and every loop that waits at all leaves its body by exactly ONE edge, its bound: a {@code break} or
 * a {@code return} on a value it read, or a second condition in its header, is a second exit, and a loop that
 * exits on what it read is a poll. Every {@link #at()} resolves exactly once. The methods that broke the loop
 * rule when it was set are a committed debt list that only shrinks.</p>
 *
 * <p><b>What it does not hold:</b> that the shape is the true one (a WINDOW that is a poll with a long span,
 * a STIMULUS that is an EXPERIMENT) and that the loop's bound is a counter rather than a value it read --
 * both are a reader's; and a wait outside a loop, which the census counts and the build does not judge.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Wait.List.class)
public @interface Wait {

    Shape shape();

    /** The fragment of the method's source the wait or loop starts with; exactly one occurrence. */
    String at();

    /** Why no link answers: what the record would have been, and why it does not say this. */
    String why();

    /** The shapes a wait that is not a link may have. */
    enum Shape {
        /** The number IS the dose -- fly 100 ticks, and the verdict is about what 100 ticks did. */
        EXPERIMENT,
        /** The ticks are how long an input is applied, or the iterations ARE the input. */
        STIMULUS,
        /** The interval between reads whose difference -- or extremum over the interval -- is the
         *  measurement. A new two-read window is written with {@code Window}, whose verdict cannot name
         *  one read alone. */
        WINDOW
    }

    /** The container javac builds when a method carries more than one claim. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        Wait[] value();
    }
}
