package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.*;

/**
 * A red-witness record in the older PROSE form: this test was seen red with production broken, told in a
 * sentence only its author could re-run. It still counts as the method's witness -- a clause its method
 * pins stays pinned -- but it is DEBT: the methods that carried one when the type was split from
 * {@link RedWitness} are listed in {@code src/test/bindings/legacy-witness-methods.txt}, the build fails
 * on one anywhere else, and the list only shrinks. When its test is touched, the record is re-taken in
 * {@link RedWitness}'s replayable form and this one is deleted.
 *
 * <p>Kept verbatim: it names the code as {@code {@code Class#member} at {@code fragment}}, which
 * is what the tool that checks a record still resolves reads.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(LegacyWitness.List.class)
public @interface LegacyWitness {

    /** The record, verbatim. */
    String prose();

    /** The container javac builds when a method carries more than one record. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List {
        LegacyWitness[] value();
    }
}
