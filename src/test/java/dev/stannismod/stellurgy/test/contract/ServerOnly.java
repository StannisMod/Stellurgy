package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.*;

/**
 * A wait in the client tier counted on the SERVER's clock alone, claimed where its method is declared:
 * whether a client reads the window is a fact about the lines after it, so the reading is stated -- the
 * subject, its reader, and why no client takes part. Orthogonal to {@link Wait}: a window may be both.
 *
 * <p>{@link #at()} is a fragment of the method's source occurring exactly once in it, the text of the line
 * the wait starts on; the build ({@code checkTestBindings}) fails on one that does not resolve. Whether the
 * reading is TRUE is a reader's.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ServerOnly.List.class)
public @interface ServerOnly {

    /** The fragment of the method's source the wait starts with; exactly one occurrence. */
    String at();

    /** The subject, its reader, and why no client is in the window. */
    String why();

    /** The container javac builds when a method carries more than one claim. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        ServerOnly[] value();
    }
}
